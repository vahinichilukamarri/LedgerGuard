package com.ledgerguard.reconciliation;

import com.ledgerguard.settlement.SettlementRecord;
import com.ledgerguard.settlement.SettlementRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs one comparison of the ledger against the external world and records what
 * it finds.
 *
 * <h2>On demand, not scheduled</h2>
 *
 * There is no cron here. Reconciliation is triggered by
 * {@code POST /reconciliation/runs} for three reasons: you almost always want
 * to run it and read the result in the same breath; a scheduled version makes
 * tests wait on wall-clock timing and hides <em>when</em> a run happened; and
 * real reconciliation is a batch job, which is closer to "triggered" than to
 * "continuous". {@code @EnableScheduling} is already on from Phase 4, so adding
 * a nightly trigger later is one annotation — deliberately not taken now.
 *
 * <h2>The grace window</h2>
 *
 * The simulator consumes events asynchronously, so a transaction committed a
 * moment ago legitimately has no settlement record yet. Reporting that as
 * MISSING_SETTLEMENT would be noise rather than a finding. Transactions younger
 * than the grace window are therefore skipped: counted as awaiting settlement,
 * neither matched nor a discrepancy. Real reconciliation runs T+1 for the same
 * reason, compressed here to seconds.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /**
     * Internal amount for a transaction is the sum of its debit legs: the gross
     * movement. Credits are the same total by the Phase 1 invariant, so either
     * side would do; debits are the conventional way to express "how much moved".
     */
    private static final String INTERNAL_SNAPSHOT = """
            SELECT t.id AS transaction_id,
                   t.currency AS currency,
                   COALESCE(SUM(CASE WHEN p.type = 'DEBIT' THEN p.amount_minor ELSE 0 END), 0) AS amount_minor,
                   t.created_at AS created_at
            FROM transactions t
            JOIN postings p ON p.transaction_id = t.id
            GROUP BY t.id, t.currency, t.created_at
            """;

    private final JdbcTemplate jdbc;
    private final SettlementRecordRepository settlements;
    private final ReconciliationRunRepository runs;
    private final ReconciliationIncidentRepository incidents;
    private final Reconciler reconciler;
    private final Clock clock;
    private final Duration graceWindow;

    public ReconciliationService(JdbcTemplate jdbc,
                                 SettlementRecordRepository settlements,
                                 ReconciliationRunRepository runs,
                                 ReconciliationIncidentRepository incidents,
                                 Clock clock,
                                 @Value("${ledgerguard.reconciliation.grace-seconds:5}") long graceSeconds) {
        this.jdbc = jdbc;
        this.settlements = settlements;
        this.runs = runs;
        this.incidents = incidents;
        this.reconciler = new Reconciler();
        this.clock = clock;
        this.graceWindow = Duration.ofSeconds(graceSeconds);
    }

    /** The outcome of one pass, before it is turned into a response. */
    public record RunResult(ReconciliationRun run,
                            List<ReconciliationIncident> incidents,
                            int awaitingSettlement,
                            int alreadyOpen) {
    }

    /**
     * Identifies a discrepancy across runs.
     *
     * <p>A disagreement nobody has dealt with is still there on the next run,
     * and reporting it again as a brand new incident turns a standing problem
     * into a stream of alerts. This key is what makes a run idempotent for
     * anything still OPEN: the same discrepancy about the same pair of records
     * is recorded once and then left alone until somebody resolves it.
     */
    private record IncidentKey(DiscrepancyType type, UUID transactionId, UUID settlementRecordId) {

        static IncidentKey of(Reconciler.Comparison comparison) {
            return new IncidentKey(comparison.type(), comparison.transactionId(), comparison.settlementRecordId());
        }

        static IncidentKey of(ReconciliationIncident incident) {
            return new IncidentKey(incident.getDiscrepancyType(),
                    incident.getTransactionId(), incident.getSettlementRecordId());
        }
    }

    @Transactional
    public RunResult run() {
        Instant startedAt = Instant.now(clock);
        Instant cutoff = startedAt.minus(graceWindow);

        List<InternalRow> internalRows = jdbc.query(INTERNAL_SNAPSHOT, (rs, i) -> new InternalRow(
                UUID.fromString(rs.getString("transaction_id")),
                rs.getLong("amount_minor"),
                rs.getString("currency").trim(),
                rs.getTimestamp("created_at").toInstant()));

        Set<UUID> tooYoung = new HashSet<>();
        List<Reconciler.InternalTransaction> internal = new ArrayList<>(internalRows.size());
        for (InternalRow row : internalRows) {
            internal.add(new Reconciler.InternalTransaction(row.transactionId(), row.amountMinor(), row.currency()));
            if (row.createdAt().isAfter(cutoff)) {
                tooYoung.add(row.transactionId());
            }
        }

        List<SettlementRecord> settlementRecords = settlements.findAll();
        List<Reconciler.ExternalRecord> external = settlementRecords.stream()
                .map(record -> new Reconciler.ExternalRecord(
                        record.getId(), record.getExternalId(), record.getExternalReference(),
                        record.getAmountMinor(), record.getCurrency(), record.getStatus().name()))
                .toList();

        List<Reconciler.Comparison> comparisons = reconciler.reconcile(internal, external);

        int matched = 0;
        int awaiting = 0;
        List<Reconciler.Comparison> toRecord = new ArrayList<>();

        for (Reconciler.Comparison comparison : comparisons) {
            // A transaction inside the grace window that has no settlement yet
            // is not a finding; the simulator simply has not caught up.
            boolean notYetSettleable = comparison.type() == DiscrepancyType.MISSING_SETTLEMENT
                    && comparison.transactionId() != null
                    && tooYoung.contains(comparison.transactionId());

            if (notYetSettleable) {
                awaiting++;
            } else if (comparison.type() == DiscrepancyType.MATCHED) {
                matched++;
            } else {
                toRecord.add(comparison);
            }
        }

        // Anything still OPEN was reported by an earlier run and has not been
        // dealt with. Finding it again is expected; filing it again is noise.
        Set<IncidentKey> alreadyOpen = incidents.findByStatus(IncidentStatus.OPEN).stream()
                .map(IncidentKey::of)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));

        Instant completedAt = Instant.now(clock);
        ReconciliationRun run = runs.save(ReconciliationRun.of(
                startedAt, completedAt, internal.size(), external.size(), matched, toRecord.size()));

        List<ReconciliationIncident> created = new ArrayList<>();
        int suppressed = 0;
        for (Reconciler.Comparison comparison : toRecord) {
            // add() returns false when this discrepancy is already on somebody's
            // desk, which also guards against duplicates within a single run.
            if (!alreadyOpen.add(IncidentKey.of(comparison))) {
                suppressed++;
                continue;
            }
            created.add(incidents.save(ReconciliationIncident.from(run.getId(), comparison, completedAt)));
        }

        log.info("reconciliation {}: {} internal, {} external, {} matched, {} discrepancies "
                        + "({} new, {} already open), {} awaiting settlement",
                run.getId(), internal.size(), external.size(), matched,
                toRecord.size(), created.size(), suppressed, awaiting);

        return new RunResult(run, created, awaiting, suppressed);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationIncident> findIncidents(DiscrepancyType type, Severity severity,
                                                     IncidentStatus status, UUID transactionId) {
        return incidents.findAll(
                ReconciliationIncidentRepository.matching(type, severity, status, transactionId));
    }

    @Transactional
    public ReconciliationIncident resolve(UUID incidentId) {
        ReconciliationIncident incident = incidents.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("incident not found: " + incidentId));
        incident.resolve(Instant.now(clock));
        return incident;
    }

    private record InternalRow(UUID transactionId, long amountMinor, String currency, Instant createdAt) {
    }
}
