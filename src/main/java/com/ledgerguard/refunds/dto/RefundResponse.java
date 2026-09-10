package com.ledgerguard.refunds.dto;

import com.ledgerguard.config.Money;
import com.ledgerguard.refunds.Refund;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.dto.TransactionResponse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The refund that was recorded, the balanced transaction it produced, and where
 * the payment now stands.
 *
 * <p>The remaining figures are derived at the moment of the response by summing
 * refund rows; nothing here is read from a stored total.
 */
public record RefundResponse(
        UUID refundId,
        UUID paymentId,
        long amountMinorUnits,
        BigDecimal amount,
        String currency,
        long paymentAmountMinorUnits,
        long refundedTotalMinorUnits,
        long remainingRefundableMinorUnits,
        BigDecimal remainingRefundable,
        TransactionResponse transaction) {

    public static RefundResponse of(Refund refund, PostedTransaction posted,
                                    long paymentAmountMinor, long refundedTotalMinor) {
        String currency = refund.getCurrency();
        long remaining = paymentAmountMinor - refundedTotalMinor;
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getAmountMinor(),
                Money.toMajorUnits(refund.getAmountMinor(), currency),
                currency,
                paymentAmountMinor,
                refundedTotalMinor,
                remaining,
                Money.toMajorUnits(remaining, currency),
                TransactionResponse.from(posted));
    }
}
