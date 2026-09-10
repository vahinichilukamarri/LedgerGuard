package com.ledgerguard.payments.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Inbound payment instruction.
 *
 * <p>{@code amount} is a {@link BigDecimal} because this is the API boundary and
 * clients speak decimals. It is converted to minor units in the controller and
 * never travels further in this form.
 */
public record CreatePaymentRequest(

        @NotNull(message = "sourceAccountId is required")
        UUID sourceAccountId,

        @NotNull(message = "destinationAccountId is required")
        UUID destinationAccountId,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        BigDecimal amount,

        @NotNull(message = "currency is required")
        @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter ISO-4217 code")
        String currency,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description) {
}
