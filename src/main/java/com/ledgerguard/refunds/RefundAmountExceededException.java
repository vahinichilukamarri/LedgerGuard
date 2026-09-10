package com.ledgerguard.refunds;

import java.util.UUID;

/**
 * Thrown when a refund would take the cumulative refunded amount past what was
 * actually paid.
 *
 * <p>Like {@code UnbalancedTransactionException}, this is raised before any
 * write is attempted, so a rejected refund leaves nothing behind.
 */
public class RefundAmountExceededException extends RuntimeException {

    public RefundAmountExceededException(String message) {
        super(message);
    }

    public static RefundAmountExceededException of(UUID paymentId, long requestedMinor,
                                                   long alreadyRefundedMinor, long paidMinor) {
        long refundable = paidMinor - alreadyRefundedMinor;
        return new RefundAmountExceededException(
                ("refund rejected for payment %s: requested %d minor units but only %d remain refundable "
                        + "(paid %d, already refunded %d)")
                        .formatted(paymentId, requestedMinor, refundable, paidMinor, alreadyRefundedMinor));
    }
}
