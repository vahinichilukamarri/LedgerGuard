package com.ledgerguard.refunds;

import com.ledgerguard.payments.Payment;
import com.ledgerguard.payments.PaymentNotFoundException;
import com.ledgerguard.payments.PaymentRepository;
import com.ledgerguard.payments.PaymentStatus;
import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.refunds.dto.RefundResponse;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Refunds money by writing a new transaction, never by editing an old one.
 *
 * <p>The original payment and its postings are left exactly as they were. That
 * is not a policy this class implements: postings are physically unmodifiable,
 * so a refund could not edit them even if it wanted to. What this class does is
 * write the opposite movement as its own balanced transaction.
 *
 * <p>Everything below runs in one database transaction. If any step fails, the
 * refund row, the transaction row and both postings all disappear together.
 */
@Service
public class RefundService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final TransactionService transactions;
    private final Clock clock;

    public RefundService(RefundRepository refunds, PaymentRepository payments,
                         TransactionService transactions, Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public RefundResponse refund(UUID paymentId, long amountMinor, String description) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("refund amount must be greater than zero, got: " + amountMinor);
        }

        // Locks the payment row for the rest of this transaction, so two refunds
        // arriving at once cannot both read the same remaining balance.
        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() != PaymentStatus.POSTED) {
            throw new IllegalArgumentException(
                    "payment %s is %s and cannot be refunded".formatted(paymentId, payment.getStatus()));
        }

        long alreadyRefunded = refunds.totalRefundedMinorUnits(paymentId);
        long refundable = payment.getAmountMinor() - alreadyRefunded;
        if (amountMinor > refundable) {
            throw RefundAmountExceededException.of(
                    paymentId, amountMinor, alreadyRefunded, payment.getAmountMinor());
        }

        String currency = payment.getCurrency();

        // The original payment credited the source and debited the destination.
        // A refund runs it backwards: the payer is made whole (DEBIT), the payee
        // gives it up (CREDIT). Equal and opposite, so it nets to zero and
        // satisfies the same balance check every other transaction goes through.
        PostedTransaction posted = transactions.createBalanced(
                describe(description, payment, amountMinor),
                currency,
                List.of(
                        NewPosting.debit(payment.getSourceAccountId(), amountMinor, currency),
                        NewPosting.credit(payment.getDestinationAccountId(), amountMinor, currency)));

        Refund refund = refunds.save(Refund.create(
                paymentId, posted.transaction().getId(), amountMinor, currency, Instant.now(clock)));

        return RefundResponse.of(refund, posted, payment.getAmountMinor(), alreadyRefunded + amountMinor);
    }

    /** Refundable amount remaining on a payment, derived from the refund rows. */
    @Transactional(readOnly = true)
    public long refundableMinorUnits(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return payment.getAmountMinor() - refunds.totalRefundedMinorUnits(paymentId);
    }

    private static String describe(String description, Payment payment, long amountMinor) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        boolean partial = amountMinor < payment.getAmountMinor();
        return "%s refund of payment %s".formatted(partial ? "Partial" : "Full", payment.getId());
    }
}
