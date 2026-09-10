package com.ledgerguard.payments;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID paymentId) {
        super("payment not found: " + paymentId);
    }
}
