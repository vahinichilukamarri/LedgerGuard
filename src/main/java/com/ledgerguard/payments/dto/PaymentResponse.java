package com.ledgerguard.payments.dto;

import com.ledgerguard.payments.Payment;
import com.ledgerguard.payments.PaymentStatus;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.dto.TransactionResponse;

import java.util.UUID;

/** The created payment, plus the balanced transaction and postings it produced. */
public record PaymentResponse(
        UUID paymentId,
        PaymentStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        TransactionResponse transaction) {

    public static PaymentResponse of(Payment payment, PostedTransaction posted) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getSourceAccountId(),
                payment.getDestinationAccountId(),
                TransactionResponse.from(posted));
    }
}
