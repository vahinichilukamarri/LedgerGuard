package com.ledgerguard.reversals;

import com.ledgerguard.outbox.EventType;
import com.ledgerguard.outbox.OutboxRecorder;
import com.ledgerguard.payments.Payment;
import com.ledgerguard.payments.PaymentRepository;
import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.PostingRepository;
import com.ledgerguard.refunds.RefundRepository;
import com.ledgerguard.reversals.dto.ReversalResponse;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.Transaction;
import com.ledgerguard.transactions.TransactionNotFoundException;
import com.ledgerguard.transactions.TransactionRepository;
import com.ledgerguard.transactions.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reverses a transaction by writing a new one whose postings exactly negate it.
 *
 * <p>This is the general case of what a refund does for payments. A refund
 * knows it is dealing with a payer and a payee and can refund part of the
 * amount; a reversal makes no such assumption. It reads whatever postings the
 * original transaction has, however many and in whatever currencies, and emits
 * the opposite of each.
 *
 * <p>Because the original balanced, its exact negation balances too. The result
 * still goes through {@code TransactionService.createBalanced}, so the check
 * runs rather than being assumed.
 *
 * <h2>Reversals and refunds are mutually exclusive</h2>
 *
 * When the transaction being reversed is a payment's, two things happen that do
 * not happen for any other transaction: the payment is refused if it has already
 * been refunded, and it is marked {@code REVERSED} so it can never be refunded
 * afterwards. Both directions have to be blocked, because a reversal negates the
 * whole original while a refund returns part of it — do both and the payer gets
 * back more than they paid, with every transaction involved still balancing
 * perfectly. See {@link RefundedPaymentCannotBeReversedException}.
 */
@Service
public class ReversalService {

    private final ReversalRepository reversals;
    private final TransactionRepository transactionRepository;
    private final PostingRepository postings;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final TransactionService transactions;
    private final OutboxRecorder outbox;
    private final Clock clock;

    public ReversalService(ReversalRepository reversals, TransactionRepository transactionRepository,
                           PostingRepository postings, PaymentRepository payments,
                           RefundRepository refunds, TransactionService transactions,
                           OutboxRecorder outbox, Clock clock) {
        this.reversals = reversals;
        this.transactionRepository = transactionRepository;
        this.postings = postings;
        this.payments = payments;
        this.refunds = refunds;
        this.transactions = transactions;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public ReversalResponse reverse(UUID transactionId, String description) {
        Transaction original = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        reversals.findByOriginalTransactionId(transactionId).ifPresent(existing -> {
            throw new TransactionAlreadyReversedException(
                    transactionId, existing.getReversalTransactionId());
        });

        // If this transaction settled a payment, the payment has to be brought
        // along: reversing it gives the money back, and nothing else must be
        // able to give it back a second time. Empty for a refund's or another
        // reversal's transaction, which guard no payment.
        //
        // The lock is taken here, before any writing, and on the same row and in
        // the same order as the refund path, so a refund and a reversal racing
        // on one payment serialise instead of both succeeding.
        Payment settled = payments.findByTransactionIdForUpdate(transactionId).orElse(null);
        if (settled != null) {
            long alreadyRefunded = refunds.totalRefundedMinorUnits(settled.getId());
            if (alreadyRefunded > 0) {
                throw new RefundedPaymentCannotBeReversedException(
                        settled.getId(), transactionId, alreadyRefunded);
            }
        }

        List<Posting> originalPostings =
                postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(transactionId);
        if (originalPostings.isEmpty()) {
            throw new IllegalStateException(
                    "transaction " + transactionId + " has no postings and cannot be reversed");
        }

        // Same account, same amount, same currency, opposite direction.
        List<NewPosting> negated = originalPostings.stream()
                .map(posting -> new NewPosting(
                        posting.getAccountId(),
                        posting.getType().opposite(),
                        posting.getAmountMinor(),
                        posting.getCurrency()))
                .toList();

        PostedTransaction posted = transactions.createBalanced(
                describe(description, original), original.getCurrency(), negated);

        Reversal reversal = reversals.save(Reversal.create(
                transactionId, posted.transaction().getId(), Instant.now(clock)));

        // Same database transaction as the negating postings, so the payment
        // cannot be left POSTED while its money has already gone back.
        if (settled != null) {
            settled.markReversed();
        }

        // Keyed on the ORIGINAL transaction id, so a consumer watching that
        // transaction sees the reversal land on the same partition, after it.
        // Gross amount moved by the reversal: the sum of its debit legs. Carried
        // on the event so a consumer never has to call back to learn how much
        // was reversed, same rule as every other payload here.
        long reversedAmountMinor = negated.stream()
                .filter(leg -> leg.type() == com.ledgerguard.postings.PostingType.DEBIT)
                .mapToLong(NewPosting::amountMinor)
                .sum();

        outbox.record(EventType.TRANSACTION_REVERSED, transactionId, Map.of(
                "reversalId", reversal.getId().toString(),
                "originalTransactionId", transactionId.toString(),
                "reversalTransactionId", posted.transaction().getId().toString(),
                "amountMinor", reversedAmountMinor,
                "currency", original.getCurrency()));

        return ReversalResponse.of(reversal, posted);
    }

    private static String describe(String description, Transaction original) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return "Reversal of transaction " + original.getId();
    }
}
