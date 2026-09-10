package com.ledgerguard.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One reconciliation pass, and where MATCHED lives.
 *
 * <p>A matched transaction produces no incident: incidents are exceptions that
 * need action, and writing a row for every agreement would bury the handful
 * that do not. The counts here keep matches observable — you can see that a run
 * examined 400 transactions and agreed on 397 — without turning the incident
 * table into a log.
 */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    @Column(name = "internal_examined", nullable = false, updatable = false)
    private int internalExamined;

    @Column(name = "external_examined", nullable = false, updatable = false)
    private int externalExamined;

    @Column(name = "matched", nullable = false, updatable = false)
    private int matched;

    @Column(name = "discrepancies", nullable = false, updatable = false)
    private int discrepancies;

    /** For Hibernate only. */
    protected ReconciliationRun() {
    }

    private ReconciliationRun(UUID id, Instant startedAt, Instant completedAt, int internalExamined,
                              int externalExamined, int matched, int discrepancies) {
        this.id = id;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.internalExamined = internalExamined;
        this.externalExamined = externalExamined;
        this.matched = matched;
        this.discrepancies = discrepancies;
    }

    public static ReconciliationRun of(Instant startedAt, Instant completedAt, int internalExamined,
                                       int externalExamined, int matched, int discrepancies) {
        return new ReconciliationRun(UUID.randomUUID(), startedAt, completedAt,
                internalExamined, externalExamined, matched, discrepancies);
    }

    public UUID getId() {
        return id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getInternalExamined() {
        return internalExamined;
    }

    public int getExternalExamined() {
        return externalExamined;
    }

    public int getMatched() {
        return matched;
    }

    public int getDiscrepancies() {
        return discrepancies;
    }
}
