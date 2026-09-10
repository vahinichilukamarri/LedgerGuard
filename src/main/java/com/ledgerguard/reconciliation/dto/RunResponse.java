package com.ledgerguard.reconciliation.dto;

import com.ledgerguard.reconciliation.ReconciliationService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one reconciliation pass found.
 *
 * <p>{@code matched} has no corresponding incident list on purpose: agreement
 * is recorded as a number, disagreement as a row. {@code awaitingSettlement}
 * counts transactions too recent for the external side to have caught up with,
 * which are neither matched nor a finding — if a run reports nothing where you
 * expected a discrepancy, this is the first number to look at.
 *
 * <p>{@code discrepancies} is how many were found; {@code newIncidents} is how
 * many were filed. They differ by {@code alreadyOpen}: discrepancies that an
 * earlier run reported and nobody has resolved yet, which are deliberately not
 * filed twice.
 */
public record RunResponse(
        UUID runId,
        Instant startedAt,
        Instant completedAt,
        int internalExamined,
        int externalExamined,
        int matched,
        int awaitingSettlement,
        int discrepancies,
        int newIncidents,
        int alreadyOpen,
        List<IncidentResponse> incidents) {

    public static RunResponse from(ReconciliationService.RunResult result) {
        return new RunResponse(
                result.run().getId(),
                result.run().getStartedAt(),
                result.run().getCompletedAt(),
                result.run().getInternalExamined(),
                result.run().getExternalExamined(),
                result.run().getMatched(),
                result.awaitingSettlement(),
                result.run().getDiscrepancies(),
                result.incidents().size(),
                result.alreadyOpen(),
                result.incidents().stream().map(IncidentResponse::from).toList());
    }
}
