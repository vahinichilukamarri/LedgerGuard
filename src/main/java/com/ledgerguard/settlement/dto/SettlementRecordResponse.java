package com.ledgerguard.settlement.dto;

import com.ledgerguard.settlement.SettlementRecord;
import com.ledgerguard.settlement.SettlementStatus;

import java.time.Instant;
import java.util.UUID;

public record SettlementRecordResponse(
        UUID id,
        String externalId,
        String externalReference,
        long amountMinor,
        String currency,
        SettlementStatus status,
        Instant settledAt) {

    public static SettlementRecordResponse from(SettlementRecord record) {
        return new SettlementRecordResponse(
                record.getId(), record.getExternalId(), record.getExternalReference(),
                record.getAmountMinor(), record.getCurrency(), record.getStatus(), record.getSettledAt());
    }
}
