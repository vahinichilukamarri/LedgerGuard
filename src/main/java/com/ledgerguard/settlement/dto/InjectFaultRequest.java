package com.ledgerguard.settlement.dto;

import com.ledgerguard.settlement.FaultType;
import com.ledgerguard.settlement.SettlementStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Ask the simulated processor to misbehave in a specific way.
 *
 * @param type          which misbehaviour
 * @param transactionId the internal transaction to target; not needed for
 *                      PHANTOM_SETTLEMENT, which by definition references nothing
 * @param amountDeltaMinor for RESTATE_AMOUNT, how far to shift the external amount
 *                         (negative means the processor settled less than we recorded)
 * @param newStatus     for CHANGE_STATUS, what the processor now claims
 * @param amountMinor   for PHANTOM_SETTLEMENT, how much the invented movement was for
 */
public record InjectFaultRequest(

        @NotNull(message = "type is required")
        FaultType type,

        UUID transactionId,
        Long amountDeltaMinor,
        SettlementStatus newStatus,
        Long amountMinor,
        String currency) {
}
