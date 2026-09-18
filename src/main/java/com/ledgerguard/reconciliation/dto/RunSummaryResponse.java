package com.ledgerguard.reconciliation.dto;

import com.ledgerguard.reconciliation.ReconciliationRun;

import java.time.Instant;
import java.util.UUID;

/**
 * A run as it appears in the history list: its own persisted counts, without
 * {@code awaitingSettlement}, {@code newIncidents} or {@code alreadyOpen} —
 * those are computed once, at run time, from state that has since moved on,
 * and {@link ReconciliationRun} does not store them. {@link RunResponse}
 * carries them because it answers the request that just produced them; this
 * answers "what ran, and when".
 */
public record RunSummaryResponse(
        UUID runId,
        Instant startedAt,
        Instant completedAt,
        int internalExamined,
        int externalExamined,
        int matched,
        int discrepancies) {

    public static RunSummaryResponse from(ReconciliationRun run) {
        return new RunSummaryResponse(
                run.getId(), run.getStartedAt(), run.getCompletedAt(),
                run.getInternalExamined(), run.getExternalExamined(), run.getMatched(), run.getDiscrepancies());
    }
}
