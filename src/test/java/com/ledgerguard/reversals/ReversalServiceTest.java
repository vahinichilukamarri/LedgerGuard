package com.ledgerguard.reversals;

import com.ledgerguard.outbox.OutboxRecorder;
import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.PostingRepository;
import com.ledgerguard.postings.PostingType;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.Transaction;
import com.ledgerguard.transactions.TransactionNotFoundException;
import com.ledgerguard.transactions.TransactionRepository;
import com.ledgerguard.transactions.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the reverse-once rule and for the exactness of the negation.
 */
class ReversalServiceTest {

    private static final String USD = "USD";

    private final ReversalRepository reversals = mock(ReversalRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final PostingRepository postings = mock(PostingRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-02-01T09:00:00Z"), ZoneOffset.UTC);

    private final OutboxRecorder outbox = mock(OutboxRecorder.class);

    private final ReversalService service = new ReversalService(
            reversals, transactionRepository, postings, transactions, outbox, clock);

    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();
    private final UUID accountC = UUID.randomUUID();
    private Transaction original;

    @BeforeEach
    void setUp() {
        original = Transaction.create("original transfer", USD, Instant.now(clock));
        when(transactionRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(reversals.findByOriginalTransactionId(original.getId())).thenReturn(Optional.empty());
    }

    private void transactionServiceAcceptsAnything() {
        when(transactions.createBalanced(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Transaction txn = Transaction.create("reversal", USD, Instant.now(clock));
                    return new PostedTransaction(txn, List.of());
                });
        when(reversals.save(any(Reversal.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("reversal postings exactly negate the original postings")
    void reversalPostingsExactlyNegateTheOriginal() {
        Instant now = Instant.now(clock);
        List<Posting> originalLegs = List.of(
                Posting.create(original.getId(), accountA, PostingType.CREDIT, 1025L, USD, now),
                Posting.create(original.getId(), accountB, PostingType.DEBIT, 1025L, USD, now));
        when(postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(original.getId()))
                .thenReturn(originalLegs);
        transactionServiceAcceptsAnything();

        service.reverse(original.getId(), "correcting an error");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewPosting>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactions).createBalanced(anyString(), anyString(), captor.capture());
        List<NewPosting> negated = captor.getValue();

        assertThat(negated).hasSize(originalLegs.size());
        for (Posting originalLeg : originalLegs) {
            NewPosting mirror = negated.stream()
                    .filter(n -> n.accountId().equals(originalLeg.getAccountId()))
                    .findFirst().orElseThrow();

            assertThat(mirror.type()).isEqualTo(originalLeg.getType().opposite());
            assertThat(mirror.amountMinor()).isEqualTo(originalLeg.getAmountMinor());
            assertThat(mirror.currency()).isEqualTo(originalLeg.getCurrency());
            assertThat(mirror.signedAmountMinor()).isEqualTo(-originalLeg.signedAmountMinor());
        }
    }

    @Test
    @DisplayName("a many-legged transaction is negated leg for leg")
    void multiLegTransactionIsNegatedCompletely() {
        Instant now = Instant.now(clock);
        when(postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(original.getId())).thenReturn(List.of(
                Posting.create(original.getId(), accountA, PostingType.CREDIT, 1000L, USD, now),
                Posting.create(original.getId(), accountB, PostingType.DEBIT, 600L, USD, now),
                Posting.create(original.getId(), accountC, PostingType.DEBIT, 400L, USD, now)));
        transactionServiceAcceptsAnything();

        service.reverse(original.getId(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewPosting>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactions).createBalanced(anyString(), anyString(), captor.capture());

        List<NewPosting> negated = captor.getValue();
        assertThat(negated).hasSize(3);
        assertThat(negated.stream().mapToLong(NewPosting::signedAmountMinor).sum())
                .as("the negation must balance on its own")
                .isZero();
        assertThat(negated).extracting(NewPosting::type)
                .containsExactlyInAnyOrder(PostingType.DEBIT, PostingType.CREDIT, PostingType.CREDIT);
    }

    @Test
    @DisplayName("reversing an already-reversed transaction is rejected")
    void secondReversalIsRejected() {
        UUID existingReversalTxn = UUID.randomUUID();
        Reversal existing = Reversal.create(original.getId(), existingReversalTxn, Instant.now(clock));
        when(reversals.findByOriginalTransactionId(original.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reverse(original.getId(), "again"))
                .isInstanceOf(TransactionAlreadyReversedException.class)
                .hasMessageContaining(original.getId().toString())
                .hasMessageContaining(existingReversalTxn.toString());

        verifyNoInteractions(transactions);
        verify(reversals, never()).save(any());
    }

    @Test
    @DisplayName("reversing an unknown transaction is a not-found")
    void unknownTransactionIsRejected() {
        UUID missing = UUID.randomUUID();
        when(transactionRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reverse(missing, null))
                .isInstanceOf(TransactionNotFoundException.class);

        verifyNoInteractions(transactions);
    }

    @Test
    @DisplayName("a transaction with no postings cannot be reversed")
    void transactionWithoutPostingsCannotBeReversed() {
        when(postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(original.getId()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.reverse(original.getId(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no postings");

        verifyNoInteractions(transactions);
    }
}
