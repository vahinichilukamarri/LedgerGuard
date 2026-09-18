package com.ledgerguard.transactions;

import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.PostingRepository;
import com.ledgerguard.postings.PostingType;
import com.ledgerguard.transactions.dto.TransactionResponse;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single write path into the ledger.
 *
 * <p><b>This class is where the core invariant lives.</b> For every transaction
 * and every currency within it:
 *
 * <pre>    sum(DEBIT amounts) == sum(CREDIT amounts)</pre>
 *
 * <p>{@link #createBalanced} checks that before issuing a single INSERT. If the
 * postings do not balance it throws {@link UnbalancedTransactionException} and
 * nothing is written: not the transaction, not the postings. Because the check
 * runs inside the same database transaction that would do the writing, there is
 * no window in which a half-written unbalanced transaction is visible.
 *
 * <p>No other class inserts into {@code transactions} or {@code postings}.
 */
@Service
public class TransactionService {

    private final TransactionRepository transactions;
    private final PostingRepository postings;
    private final EntityManager entityManager;
    private final Clock clock;

    public TransactionService(TransactionRepository transactions, PostingRepository postings,
                              EntityManager entityManager, Clock clock) {
        this.transactions = transactions;
        this.postings = postings;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Validate that the given postings balance, then persist the transaction and
     * its postings atomically.
     *
     * @throws UnbalancedTransactionException if debits do not equal credits for
     *                                        every currency represented
     */
    @Transactional
    public PostedTransaction createBalanced(String description, String currency, List<NewPosting> newPostings) {
        requireBalanced(newPostings);

        Instant now = Instant.now(clock);
        Transaction transaction = transactions.save(Transaction.create(description, currency, now));

        List<Posting> written = new ArrayList<>(newPostings.size());
        for (NewPosting requested : newPostings) {
            Posting posting = Posting.create(
                    transaction.getId(),
                    requested.accountId(),
                    requested.type(),
                    requested.amountMinor(),
                    requested.currency(),
                    now);
            entityManager.persist(posting); // insert-only; postings are never updated
            written.add(posting);
        }
        entityManager.flush();

        return new PostedTransaction(transaction, written);
    }

    /** Read-side listing for the ledger UI: all transactions, or only those touching one account. */
    @Transactional(readOnly = true)
    public Page<Transaction> list(UUID accountId, Pageable pageable) {
        if (accountId != null) {
            return transactions.findByAccountId(accountId, pageable);
        }
        return transactions.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID transactionId) {
        Transaction transaction = transactions.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        List<Posting> legs = postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(transactionId);
        return TransactionResponse.from(transaction, legs);
    }

    /**
     * The invariant check itself, kept separate and free of persistence concerns
     * so it can be reasoned about and unit tested on its own.
     *
     * <p>Sums are accumulated with {@link Math#addExact} so an overflow becomes a
     * loud failure rather than a wrapped-around total that happens to net to zero.
     */
    void requireBalanced(List<NewPosting> newPostings) {
        if (newPostings == null || newPostings.isEmpty()) {
            throw new UnbalancedTransactionException("transaction rejected: it has no postings");
        }
        if (newPostings.size() < 2) {
            throw new UnbalancedTransactionException(
                    "transaction rejected: a balanced transaction needs at least two postings, got "
                            + newPostings.size());
        }

        Map<String, Long> debits = new LinkedHashMap<>();
        Map<String, Long> credits = new LinkedHashMap<>();
        Map<String, Long> net = new LinkedHashMap<>();

        for (NewPosting posting : newPostings) {
            String postingCurrency = posting.currency();
            if (posting.type() == PostingType.DEBIT) {
                debits.merge(postingCurrency, posting.amountMinor(), Math::addExact);
            } else {
                credits.merge(postingCurrency, posting.amountMinor(), Math::addExact);
            }
            net.merge(postingCurrency, posting.signedAmountMinor(), Math::addExact);
        }

        boolean balanced = net.values().stream().allMatch(value -> value == 0L);
        if (!balanced) {
            throw UnbalancedTransactionException.of(net, debits, credits);
        }
    }
}
