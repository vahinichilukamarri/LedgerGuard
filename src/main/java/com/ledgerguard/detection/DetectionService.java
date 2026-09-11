package com.ledgerguard.detection;

import com.ledgerguard.accounts.Account;
import com.ledgerguard.accounts.AccountRepository;
import com.ledgerguard.accounts.AccountNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Loads what the signals need and scores it.
 *
 * <h2>Read-only, by construction</h2>
 *
 * Every method here is {@code @Transactional(readOnly = true)} and every
 * statement is a SELECT. The detection layer adds no write path to a system
 * whose central claim is that money moves only through balanced postings, and it
 * stores no derived state of its own.
 *
 * <p>That last part was a real choice. A per-account rolling-statistics table
 * would make scoring cheaper, and it is what most systems reach for. It is not
 * here for the same reason account balances are derived rather than stored: a
 * cached aggregate can disagree with the rows it summarises, and a detector that
 * disagrees with the ledger is worse than no detector — it produces alerts
 * nobody can reproduce from the data. If scoring ever becomes too slow to do
 * live, the answer is a materialised view the database keeps honest, not
 * application code maintaining a second copy of the truth.
 *
 * <h2>Windows and the clock</h2>
 *
 * {@code asOf} is always passed in or read from the injected {@link Clock},
 * never from {@code Instant.now()}, so a test can place the whole computation at
 * a fixed point in time without waiting for it.
 */
@Service
public class DetectionService {

    /** Payments this account sent, in the baseline period, oldest first. */
    private static final String OUTBOUND_PAYMENTS = """
            SELECT id, amount_minor, created_at
            FROM payments
            WHERE source_account_id = ? AND created_at > ? AND created_at <= ?
            ORDER BY created_at, id
            """;

    /**
     * Transactions this account took part in during the window.
     *
     * <p>Counted from {@code postings} rather than {@code payments}, because the
     * account is a party to refunds and reversals it did not initiate, and those
     * are transactions reconciliation will judge it on.
     */
    private static final String WINDOW_TRANSACTIONS = """
            SELECT COUNT(DISTINCT transaction_id)
            FROM postings
            WHERE account_id = ? AND created_at > ? AND created_at <= ?
            """;

    /**
     * How many of those raised an incident.
     *
     * <p>Membership of the window is decided by the <em>transaction's</em> time,
     * not the incident's: reconciliation runs after the fact, sometimes long
     * after, and judging a transaction by when somebody happened to notice it
     * would make the rate depend on the reconciliation schedule.
     */
    private static final String WINDOW_MISMATCHES = """
            SELECT COUNT(DISTINCT p.transaction_id)
            FROM postings p
            WHERE p.account_id = ? AND p.created_at > ? AND p.created_at <= ?
              AND EXISTS (SELECT 1 FROM reconciliation_incidents i
                          WHERE i.transaction_id = p.transaction_id)
            """;

    private static final String WINDOW_PAYMENTS = """
            SELECT COUNT(*)
            FROM payments
            WHERE source_account_id = ? AND created_at > ? AND created_at <= ?
            """;

    private static final String WINDOW_RETURNED_PAYMENTS = """
            SELECT COUNT(*)
            FROM payments p
            WHERE p.source_account_id = ? AND p.created_at > ? AND p.created_at <= ?
              AND (EXISTS (SELECT 1 FROM refunds r WHERE r.payment_id = p.id)
                OR EXISTS (SELECT 1 FROM reversals v WHERE v.original_transaction_id = p.transaction_id))
            """;

    private static final String GLOBAL_RATES = """
            SELECT
              (SELECT COUNT(*) FROM transactions) AS total_transactions,
              (SELECT COUNT(DISTINCT transaction_id) FROM reconciliation_incidents
               WHERE transaction_id IS NOT NULL) AS mismatched_transactions,
              (SELECT COUNT(*) FROM payments) AS total_payments,
              (SELECT COUNT(*) FROM payments p
               WHERE EXISTS (SELECT 1 FROM refunds r WHERE r.payment_id = p.id)
                  OR EXISTS (SELECT 1 FROM reversals v
                             WHERE v.original_transaction_id = p.transaction_id)) AS returned_payments
            """;

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final AnomalyScorer scorer;
    private final DetectionSettings settings;
    private final Clock clock;

    public DetectionService(JdbcTemplate jdbc, AccountRepository accounts, Clock clock,
                            @Value("${ledgerguard.detection.recent-window-minutes:60}") long recentWindowMinutes,
                            @Value("${ledgerguard.detection.baseline-window-days:30}") long baselineWindowDays) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.clock = clock;

        DetectionSettings defaults = DetectionSettings.defaults();
        this.settings = new DetectionSettings(
                Duration.ofMinutes(recentWindowMinutes),
                Duration.ofDays(baselineWindowDays),
                defaults.amountHistoryCap(),
                defaults.minAmountSamples(),
                defaults.minBaselineEvents(),
                defaults.minRateTrials(),
                defaults.degenerateCeiling());
        this.scorer = AnomalyScorer.withAllSignals(settings);
    }

    @Transactional(readOnly = true)
    public AnomalyScore scoreAccount(UUID accountId) {
        return scoreAccount(accountId, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public AnomalyScore scoreAccount(UUID accountId, Instant asOf) {
        return assess(accountId, asOf).score();
    }

    /**
     * An account's activity together with its statistical score.
     *
     * <p>Both, because the ML layer needs the raw activity to derive features the
     * statistical signals do not expose — absolute amounts, elapsed time,
     * historical rank. Returning the pair keeps the SQL in this class rather than
     * having a second layer grow its own copy of it.
     */
    public record ScoredAccount(AccountActivity activity, AnomalyScore score) {
    }

    @Transactional(readOnly = true)
    public ScoredAccount assess(UUID accountId, Instant asOf) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        AccountActivity activity = activityOf(account, asOf, globalRates());
        return new ScoredAccount(activity, scorer.score(activity));
    }

    /**
     * Every account, assessed against one shared population baseline.
     *
     * <p>Ordered by account id rather than left in whatever order the repository
     * returned. Nothing downstream should depend on that order, but the forest
     * trains on this list and a forest is only reproducible if its training rows
     * arrive the same way every time — the Phase 7 lesson about iteration order
     * applies to model training more sharply than to anything before it.
     */
    @Transactional(readOnly = true)
    public List<ScoredAccount> assessAll(Instant asOf) {
        AccountActivity.GlobalRates rates = globalRates();
        return accounts.findAll().stream()
                .sorted(Comparator.comparing(Account::getId))
                .map(account -> {
                    AccountActivity activity = activityOf(account, asOf, rates);
                    return new ScoredAccount(activity, scorer.score(activity));
                })
                .toList();
    }

    /**
     * Every account, scored and ranked.
     *
     * <p>The global rates are read once and shared across all of them, so every
     * account in one ranking is judged against the same population. Recomputing
     * per account would be slower and, worse, would let the baseline shift
     * mid-ranking.
     */
    @Transactional(readOnly = true)
    public List<AnomalyScore> rankAccounts(double minimumScore, Instant asOf) {
        return assessAll(asOf).stream()
                .map(ScoredAccount::score)
                .filter(score -> score.composite() >= minimumScore)
                .sorted(Comparator.comparingDouble(AnomalyScore::composite).reversed()
                        .thenComparing(AnomalyScore::accountId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AnomalyScore> rankAccounts(double minimumScore) {
        return rankAccounts(minimumScore, Instant.now(clock));
    }

    public DetectionSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------ loading

    private AccountActivity activityOf(Account account, Instant asOf, AccountActivity.GlobalRates rates) {
        Instant baselineStart = asOf.minus(settings.baselineWindow());
        Instant windowStart = asOf.minus(settings.recentWindow());
        UUID id = account.getId();

        List<AccountActivity.PaymentEvent> payments = jdbc.query(OUTBOUND_PAYMENTS,
                (row, index) -> new AccountActivity.PaymentEvent(
                        UUID.fromString(row.getString("id")),
                        row.getLong("amount_minor"),
                        row.getTimestamp("created_at").toInstant()),
                id, Timestamp.from(baselineStart), Timestamp.from(asOf));

        return new AccountActivity(
                id,
                account.getCurrency(),
                asOf,
                settings.recentWindow(),
                settings.baselineWindow(),
                payments,
                countInWindow(WINDOW_TRANSACTIONS, id, windowStart, asOf),
                countInWindow(WINDOW_MISMATCHES, id, windowStart, asOf),
                countInWindow(WINDOW_PAYMENTS, id, windowStart, asOf),
                countInWindow(WINDOW_RETURNED_PAYMENTS, id, windowStart, asOf),
                rates);
    }

    private long countInWindow(String sql, UUID accountId, Instant from, Instant to) {
        Long value = jdbc.queryForObject(sql, Long.class,
                accountId, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0L : value;
    }

    private AccountActivity.GlobalRates globalRates() {
        return jdbc.queryForObject(GLOBAL_RATES, (row, index) -> new AccountActivity.GlobalRates(
                row.getLong("total_transactions"),
                row.getLong("mismatched_transactions"),
                row.getLong("total_payments"),
                row.getLong("returned_payments")));
    }
}
