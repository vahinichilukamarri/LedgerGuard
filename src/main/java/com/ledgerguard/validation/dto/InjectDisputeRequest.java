package com.ledgerguard.validation.dto;

import com.ledgerguard.validation.DisputeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

/**
 * A chargeback, as a scheme would raise it.
 *
 * @param raisedAt when the scheme raised it. Defaults to now; setting it in the
 *                 future is how a demo shows that a dispute which exists is
 *                 still not a label until its date arrives
 */
public record InjectDisputeRequest(
        @NotNull UUID transactionId,
        @NotNull DisputeReason reason,
        @PositiveOrZero long amountMinor,
        String currency,
        Instant raisedAt) {
}
