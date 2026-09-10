package com.ledgerguard.transactions;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(UUID transactionId) {
        super("transaction not found: " + transactionId);
    }
}
