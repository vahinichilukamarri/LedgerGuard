package com.ledgerguard.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One recorded disagreement between the ledger and the outside world.
 *
 * <p><b>Evidence is the point.</b> {@code transactionId} and
 * {@code settlementRecordId} name the exact pair of rows the engine compared,
 * so whoever investigates never has to reconstruct what it was looking at. Each
 * is nullable because two of the six types have only one side by definition:
 * MISSING_SETTLEMENT has no external record, and
 * UNEXPECTED_EXTERNAL_TRANSACTION has no internal transaction. A database CHECK
 * enforces that at least one is present, since an incident pointing at nothing
 * is evidence of nothing.
 */
@Entity
@Table(name = "reconciliation_incidents")
public class ReconciliationIncident {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, updatable = false, length = 40)
    private DiscrepancyType discrepancyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private IncidentStatus status;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "settlement_record_id", updatable = false)
    private UUID settlementRecordId;

    @Column(name = "internal_amount_minor", updatable = false)
    private Long internalAmountMinor;

    @Column(name = "external_amount_minor", updatable = false)
    private Long externalAmountMinor;

    @Column(name = "difference_minor", updatable = false)
    private Long differenceMinor;

    @Column(name = "currency", updatable = false, length = 3)
    private String currency;

    @Column(name = "internal_status", updatable = false, length = 20)
    private String internalStatus;

    @Column(name = "external_status", updatable = false, length = 20)
    private String externalStatus;

    @Column(name = "detail", nullable = false, updatable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** For Hibernate only. */
    protected ReconciliationIncident() {
    }

    public static ReconciliationIncident from(UUID runId, Reconciler.Comparison comparison, Instant detectedAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(comparison, "comparison");
        if (!comparison.type().isDiscrepancy()) {
            throw new IllegalArgumentException(
                    "MATCHED comparisons do not become incidents; run counters record them instead");
        }

        ReconciliationIncident incident = new ReconciliationIncident();
        incident.id = UUID.randomUUID();
        incident.runId = runId;
        incident.discrepancyType = comparison.type();
        incident.severity = comparison.severity();
        incident.status = IncidentStatus.OPEN;
        incident.transactionId = comparison.transactionId();
        incident.settlementRecordId = comparison.settlementRecordId();
        incident.internalAmountMinor = comparison.internalAmountMinor();
        incident.externalAmountMinor = comparison.externalAmountMinor();
        incident.differenceMinor = comparison.differenceMinor();
        incident.currency = comparison.currency();
        incident.internalStatus = comparison.internalStatus();
        incident.externalStatus = comparison.externalStatus();
        incident.detail = comparison.detail();
        incident.createdAt = detectedAt;
        return incident;
    }

    public void resolve(Instant when) {
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = Objects.requireNonNull(when, "when");
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public DiscrepancyType getDiscrepancyType() {
        return discrepancyType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getSettlementRecordId() {
        return settlementRecordId;
    }

    public Long getInternalAmountMinor() {
        return internalAmountMinor;
    }

    public Long getExternalAmountMinor() {
        return externalAmountMinor;
    }

    public Long getDifferenceMinor() {
        return differenceMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getInternalStatus() {
        return internalStatus;
    }

    public String getExternalStatus() {
        return externalStatus;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
