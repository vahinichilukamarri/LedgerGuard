package com.ledgerguard.refunds.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Inbound refund instruction.
 *
 * <p>No currency field: a refund is always in the currency of the payment it
 * refunds, so taking one from the caller would only create a way to disagree
 * with the payment.
 */
public record CreateRefundRequest(

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        BigDecimal amount,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description) {
}
