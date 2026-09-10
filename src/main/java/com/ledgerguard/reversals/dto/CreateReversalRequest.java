package com.ledgerguard.reversals.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body for a reversal.
 *
 * <p>A reversal takes no amount: it always negates the whole original
 * transaction. Accepting an amount would make partial reversals expressible,
 * and a partial correction of a payment is a refund, which has its own endpoint.
 */
public record CreateReversalRequest(

        @Size(max = 500, message = "description must be at most 500 characters")
        String description) {
}
