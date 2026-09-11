package com.ledgerguard.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.accounts.AccountService;
import com.ledgerguard.chaos.TickingClock;
import com.ledgerguard.config.Money;
import com.ledgerguard.payments.PaymentController;
import com.ledgerguard.payments.dto.CreatePaymentRequest;
import com.ledgerguard.reconciliation.ReconciliationService;
import com.ledgerguard.refunds.RefundController;
import com.ledgerguard.refunds.dto.CreateRefundRequest;
import com.ledgerguard.settlement.SettlementSimulator;
import com.ledgerguard.support.LedgerPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signals against a real ledger, reconciled for real.
 *
 * <p>The unit tests prove each signal computes correctly from a hand-built
 * sample. They cannot prove the SQL underneath assembles that sample from the
 * actual tables, which is where a detection layer usually goes wrong: a window
 * boundary off by one, a join that counts a transaction twice, a rate measured
 * against the wrong denominator. Everything here goes through the real
 * controllers, the real reconciler and the real migrated schema.
 *
 * <h2>Determinism</h2>
 *
 * Time is supplied by {@link TickingClock}, reused from Phase 7, and advanced
 * explicitly. Nothing sleeps and nothing reads a wall clock, so payment
 * timestamps land exactly where the scenario puts them and the windows they fall
 * into never depend on how fast the machine is.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ledgerguard.outbox.publisher.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.admin.auto-create=false",
                "ledgerguard.reconciliation.grace-seconds=5",
                "logging.level.com.ledgerguard=WARN",
                "logging.level.org.springframework=WARN",
                "logging.level.org.hibernate=WARN"
        })
@Import(DetectionFlowIntegrationTest.FixedClockConfig.class)
class DetectionFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = start();

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = LedgerPostgres.newContainer();
        container.start();
        return container;
    }

    static final Instant EPOCH = Instant.parse("2026-09-11T09:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        TickingClock detectionClock() {
            return new TickingClock(EPOCH);
        }
    }

    private static final String TRUNCATE_ALL = """
            TRUNCATE TABLE reconciliation_incidents, reconciliation_runs, settlement_records,
                           processed_events, outbox_events, idempotency_keys,
                           reversals, refunds, postings, payments, transactions, accounts
            RESTART IDENTITY CASCADE
            """;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TickingClock clock;
    @Autowired
    private AccountService accounts;
    @Autowired
    private PaymentController payments;
    @Autowired
    private RefundController refunds;
    @Autowired
    private DetectionService detection;
    @Autowired
    private ReconciliationService reconciliation;
    @Autowired
    private SettlementSimulator simulator;
    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void knownStartingState() {
        clock.resetTo(EPOCH);
        jdbc.execute(TRUNCATE_ALL);
    }

    @Test
    @DisplayName("an account behaving exactly as it always has scores near zero on real data")
    void steadyAccountScoresLow() {
        UUID payer = account();
        UUID payee = account();

        // Thirty days of one payment a day, with ordinary variation in amount.
        buildDailyHistory(payer, payee, 30, 10_000, 250);

        // One more, in keeping.
        clock.advance(Duration.ofDays(1));
        pay(payer, payee, 10_100);
        Instant asOf = clock.instant().plus(Duration.ofMinutes(1));

        AnomalyScore score = detection.scoreAccount(payer, asOf);

        assertThat(score.composite())
                .as("a steady account must not be flagged, or the layer is unusable: %s",
                        explain(score))
                .isLessThan(0.2);
        assertThat(score.firedSignals()).isEmpty();
        assertThat(score.applicableSignals())
                .as("and it must be scored on real evidence, not excused for lack of it")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a burst of unusually large payments lights up several signals at once")
    void burstOfLargePaymentsScoresHigh() {
        UUID payer = account();
        UUID payee = account();

        buildDailyHistory(payer, payee, 30, 10_000, 250);

        // Six payments, each two hundred times the usual, seconds apart.
        clock.advance(Duration.ofDays(1));
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofSeconds(2));
            pay(payer, payee, 2_000_000);
        }
        Instant asOf = clock.instant().plus(Duration.ofMinutes(1));

        AnomalyScore score = detection.scoreAccount(payer, asOf);

        assertThat(score.composite())
                .as("an obvious anomaly on real data: %s", explain(score))
                .isGreaterThan(0.6);
        assertThat(score.firedSignals()).extracting(SignalScore::signal)
                .as("amount, velocity and burst are three different observations of one event")
                .contains(Signal.AMOUNT_OUTLIER, Signal.VELOCITY, Signal.BURST);
        assertThat(score.isWellEvidenced()).isTrue();

        // The amount signal must name the payment, not merely the account.
        SignalScore amount = signalOf(score, Signal.AMOUNT_OUTLIER);
        assertThat(amount.subjectId()).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM payments WHERE id = ?", amount.subjectId()))
                .as("the id it reports has to be a real payment")
                .isEqualTo(1);
    }

    /**
     * The reconciliation signal, driven by genuinely reconciled data: one
     * account's payments are settled by the simulator, the other's are not, and
     * the real reconciler decides what that means.
     */
    @Test
    @DisplayName("the mismatch signal fires only for the account whose transactions failed reconciliation")
    void reconciliationMismatchIsAttributedToTheRightAccount() {
        UUID settledPayer = account();
        UUID settledPayee = account();
        UUID unsettledPayer = account();
        UUID unsettledPayee = account();

        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofMinutes(1));
            pay(settledPayer, settledPayee, 5_000);
            clock.advance(Duration.ofMinutes(1));
            pay(unsettledPayer, unsettledPayee, 5_000);
        }

        // The external processor settles one account's transactions and never
        // hears about the other's. Events are taken from the outbox exactly as
        // written, so what is settled is what the ledger actually published.
        settleTransactionsOf(settledPayer);

        clock.advance(Duration.ofMinutes(30));
        ReconciliationService.RunResult run = reconciliation.run();
        assertThat(run.incidents())
                .as("the reconciler must have found the unsettled side")
                .isNotEmpty();

        Instant asOf = clock.instant();
        SignalScore clean = signalOf(detection.scoreAccount(settledPayer, asOf),
                Signal.RECONCILIATION_MISMATCH_RATE);
        SignalScore dirty = signalOf(detection.scoreAccount(unsettledPayer, asOf),
                Signal.RECONCILIATION_MISMATCH_RATE);

        assertThat(dirty.applicable()).isTrue();
        assertThat(dirty.fired())
                .as("the account whose transactions failed reconciliation: %s", dirty.explanation())
                .isTrue();

        assertThat(clean.fired())
                .as("the settled account must not be tarred by its neighbour: %s", clean.explanation())
                .isFalse();
    }

    @Test
    @DisplayName("an account whose payments keep coming back fires the return-rate signal")
    void refundRateIsComputedFromRealRefunds() {
        UUID payer = account();
        UUID payee = account();
        UUID quietPayer = account();
        UUID quietPayee = account();

        // A population where returns are rare.
        for (int i = 0; i < 30; i++) {
            clock.advance(Duration.ofMinutes(1));
            pay(quietPayer, quietPayee, 4_000);
        }

        // This account's payments almost all come straight back.
        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofMinutes(1));
            JsonNode payment = pay(payer, payee, 4_000);
            if (i < 10) {
                refund(UUID.fromString(payment.get("paymentId").asText()), 4_000);
            }
        }

        Instant asOf = clock.instant();
        SignalScore returns = signalOf(detection.scoreAccount(payer, asOf),
                Signal.REFUND_REVERSAL_RATE);
        SignalScore quiet = signalOf(detection.scoreAccount(quietPayer, asOf),
                Signal.REFUND_REVERSAL_RATE);

        assertThat(returns.fired()).as(returns.explanation()).isTrue();
        assertThat(quiet.fired()).as(quiet.explanation()).isFalse();
    }

    @Test
    @DisplayName("the ranking puts the anomalous account above the steady one")
    void rankingOrdersByCompositeScore() {
        UUID steadyPayer = account();
        UUID steadyPayee = account();
        UUID burstPayer = account();
        UUID burstPayee = account();

        buildDailyHistory(steadyPayer, steadyPayee, 30, 10_000, 250);
        buildDailyHistory(burstPayer, burstPayee, 30, 10_000, 250);

        clock.advance(Duration.ofDays(1));
        pay(steadyPayer, steadyPayee, 10_050);
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofSeconds(2));
            pay(burstPayer, burstPayee, 3_000_000);
        }
        Instant asOf = clock.instant().plus(Duration.ofMinutes(1));

        List<AnomalyScore> ranked = detection.rankAccounts(0.0, asOf);

        assertThat(ranked).isNotEmpty();
        assertThat(ranked.get(0).accountId())
                .as("worst first is the whole point of the endpoint")
                .isEqualTo(burstPayer);
        assertThat(ranked).isSortedAccordingTo(
                (left, right) -> Double.compare(right.composite(), left.composite()));
    }

    @Test
    @DisplayName("scoring the same ledger twice gives byte-identical answers")
    void scoringIsDeterministic() {
        UUID payer = account();
        UUID payee = account();
        buildDailyHistory(payer, payee, 30, 10_000, 250);
        clock.advance(Duration.ofDays(1));
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(3));
            pay(payer, payee, 900_000);
        }
        Instant asOf = clock.instant().plus(Duration.ofMinutes(1));

        AnomalyScore first = detection.scoreAccount(payer, asOf);
        AnomalyScore second = detection.scoreAccount(payer, asOf);

        assertThat(first.composite()).isEqualTo(second.composite());
        assertThat(first.applicableSignals()).isEqualTo(second.applicableSignals());
        for (int i = 0; i < first.signals().size(); i++) {
            assertThat(first.signals().get(i).explanation())
                    .as("even the wording must be stable, or a diff of two runs is unreadable")
                    .isEqualTo(second.signals().get(i).explanation());
        }
    }

    @Test
    @DisplayName("a brand-new account is reported as unmeasured, not as safe")
    void newAccountIsNotFalselyReassuring() {
        UUID payer = account();
        UUID payee = account();
        clock.advance(Duration.ofMinutes(5));
        pay(payer, payee, 999_999);

        AnomalyScore score = detection.scoreAccount(payer, clock.instant());

        assertThat(score.applicableSignals())
                .as("nothing about one payment is measurable")
                .isZero();
        assertThat(score.isWellEvidenced()).isFalse();
        assertThat(score.signals())
                .allSatisfy(signal -> assertThat(signal.explanation()).contains("insufficient data"));
    }

    // --------------------------------------------------------------- helpers

    private UUID account() {
        return accounts.create("detect-" + UUID.randomUUID(), "USD").getId();
    }

    /** {@code days} payments, one per day, alternating around {@code baseMinor}. */
    private void buildDailyHistory(UUID payer, UUID payee, int days, long baseMinor, long spread) {
        for (int i = 0; i < days; i++) {
            clock.advance(Duration.ofDays(1));
            long amount = baseMinor + (i % 2 == 0 ? spread : -spread) * (1 + i % 5);
            pay(payer, payee, amount);
        }
    }

    private JsonNode pay(UUID source, UUID destination, long amountMinor) {
        return parse(payments.create("detect-" + UUID.randomUUID(),
                new CreatePaymentRequest(source, destination,
                        Money.toMajorUnits(amountMinor, "USD"), "USD", null)));
    }

    private void refund(UUID paymentId, long amountMinor) {
        refunds.refund(paymentId, "detect-" + UUID.randomUUID(),
                new CreateRefundRequest(Money.toMajorUnits(amountMinor, "USD"), null));
    }

    /** Feed this account's published events to the external processor. */
    private void settleTransactionsOf(UUID accountId) {
        List<String> payloads = jdbc.queryForList("""
                SELECT e.payload FROM outbox_events e
                WHERE e.aggregate_id IN (SELECT id FROM payments WHERE source_account_id = ?)
                ORDER BY e.occurred_at
                """, String.class, accountId);

        payloads.forEach(simulator::onLedgerEvent);
    }

    private JsonNode parse(org.springframework.http.ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("controller returned unparseable JSON", e);
        }
    }

    private static SignalScore signalOf(AnomalyScore score, Signal signal) {
        return score.signals().stream()
                .filter(candidate -> candidate.signal() == signal)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + signal + " in " + score));
    }

    private static String explain(AnomalyScore score) {
        StringBuilder text = new StringBuilder("composite=%.3f over %d signals"
                .formatted(score.composite(), score.applicableSignals()));
        score.signals().forEach(signal -> text.append("\n  ")
                .append(signal.signal().wireName()).append(": ").append(signal.explanation()));
        return text.toString();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
