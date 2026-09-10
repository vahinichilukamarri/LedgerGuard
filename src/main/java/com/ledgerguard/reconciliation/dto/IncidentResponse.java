package com.ledgerguard.reconciliation.dto;

import com.ledgerguard.reconciliation.DiscrepancyType;
import com.ledgerguard.reconciliation.IncidentStatus;
import com.ledgerguard.reconciliation.ReconciliationIncident;
import com.ledgerguard.reconciliation.Severity;

import java.time.Instant;
import java.util.UUID;

/**
 * An incident as returned by the API.
 *
 * <p>{@code transactionId} and {@code settlementRecordId} are the evidence:
 * follow them to the exact internal transaction and external record that were
 * compared. Amounts stay in integer minor units here, as everywhere else.
 */
public record IncidentResponse(
        UUID id,
        UUID runId,
        DiscrepancyType type,
        Severity severity,
        IncidentStatus status,
        UUID transactionId,
        UUID settlementRecordId,
        Long internalAmountMinor,
        Long externalAmountMinor,
        Long differenceMinor,
        String currency,
        String internalStatus,
        String externalStatus,
        String detail,
        Instant createdAt,
        Instant resolvedAt) {

    public static IncidentResponse from(ReconciliationIncident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getRunId(),
                incident.getDiscrepancyType(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getTransactionId(),
                incident.getSettlementRecordId(),
                incident.getInternalAmountMinor(),
                incident.getExternalAmountMinor(),
                incident.getDifferenceMinor(),
                incident.getCurrency(),
                incident.getInternalStatus(),
                incident.getExternalStatus(),
                incident.getDetail(),
                incident.getCreatedAt(),
                incident.getResolvedAt());
    }
}
