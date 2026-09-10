package com.ledgerguard.reversals;

import java.util.UUID;

/**
 * Thrown when a transaction that has already been reversed is submitted for
 * reversal again.
 *
 * <p>Reversing twice would negate the original and then negate the negation,
 * leaving the ledger exactly where it started while implying two corrections
 * happened. The database also refuses this through a UNIQUE constraint; this
 * exception exists so the API can explain it rather than surfacing a
 * constraint violation.
 */
public class TransactionAlreadyReversedException extends RuntimeException {

    public TransactionAlreadyReversedException(UUID transactionId, UUID existingReversalTransactionId) {
        super("transaction %s has already been reversed by transaction %s"
                .formatted(transactionId, existingReversalTransactionId));
    }

    public TransactionAlreadyReversedException(UUID transactionId) {
        super("transaction " + transactionId + " has already been reversed");
    }
}
