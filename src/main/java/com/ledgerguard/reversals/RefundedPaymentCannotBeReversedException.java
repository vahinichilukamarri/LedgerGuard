package com.ledgerguard.reversals;

import java.util.UUID;

/**
 * Refusal to reverse a payment that has already been refunded, in whole or in
 * part.
 *
 * <h2>Why this rule exists</h2>
 *
 * A reversal negates the <em>original</em> transaction in full. A refund has
 * already given part of that money back through its own transaction. Doing both
 * returns more than was ever paid — the payer ends up better off than before
 * they paid, with every individual transaction still perfectly balanced, which
 * is exactly why the per-transaction invariant does not catch it.
 *
 * <p>The two operations answer different questions. "Give some of it back" is a
 * refund. "This should never have happened" is a reversal, and it only means
 * anything if nothing has been given back yet. So a payment with refunds against
 * it can still be refunded up to its cap, but can no longer be reversed.
 *
 * <p>Found by Phase 6 property testing; see the bug log in README.md.
 */
public class RefundedPaymentCannotBeReversedException extends RuntimeException {

    private final UUID paymentId;
    private final UUID transactionId;
    private final long refundedMinorUnits;

    public RefundedPaymentCannotBeReversedException(UUID paymentId, UUID transactionId,
                                                    long refundedMinorUnits) {
        super("transaction %s settled payment %s, which has already been refunded %d minor units; "
                .formatted(transactionId, paymentId, refundedMinorUnits)
                + "reversing it as well would return more than was paid. "
                + "Refund the remainder instead.");
        this.paymentId = paymentId;
        this.transactionId = transactionId;
        this.refundedMinorUnits = refundedMinorUnits;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public long getRefundedMinorUnits() {
        return refundedMinorUnits;
    }
}
