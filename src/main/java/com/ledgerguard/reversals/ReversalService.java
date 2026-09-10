package com.ledgerguard.reversals;

import com.ledgerguard.outbox.EventType;
import com.ledgerguard.outbox.OutboxRecorder;
import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.PostingRepository;
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
 */
@Service
public class ReversalService {

    private final ReversalRepository reversals;
    private final TransactionRepository transactionRepository;
    private final PostingRepository postings;
    private final TransactionService transactions;
    private final OutboxRecorder outbox;
    private final Clock clock;

    public ReversalService(ReversalRepository reversals, TransactionRepository transactionRepository,
                           PostingRepository postings, TransactionService transactions,
                           OutboxRecorder outbox, Clock clock) {
        this.reversals = reversals;
        this.transactionRepository = transactionRepository;
        this.postings = postings;
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

        // Keyed on the ORIGINAL transaction id, so a consumer watching that
        // transaction sees the reversal land on the same partition, after it.
        outbox.record(EventType.TRANSACTION_REVERSED, transactionId, Map.of(
                "reversalId", reversal.getId().toString(),
                "originalTransactionId", transactionId.toString(),
                "reversalTransactionId", posted.transaction().getId().toString(),
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
