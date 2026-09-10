package com.ledgerguard.transactions;

import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.postings.Posting;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the core ledger invariant: for every transaction and currency,
 * the sum of debits must equal the sum of credits.
 *
 * <p>These run without Spring or a database on purpose. The invariant is a
 * property of the service, not of the schema, and it must hold before any
 * persistence machinery is involved.
 */
class TransactionServiceBalanceTest {

    private static final String USD = "USD";
    private static final String EUR = "EUR";

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    private final TransactionService service =
            new TransactionService(transactionRepository, entityManager, clock);

    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();

    private void repositorySavesWhateverItIsGiven() {
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("rejects postings that do not balance")
    class Unbalanced {

        @Test
        @DisplayName("a debit larger than its matching credit is rejected")
        void debitExceedingCreditIsRejected() {
            List<NewPosting> lopsided = List.of(
                    NewPosting.debit(accountA, 1025L, USD),
                    NewPosting.credit(accountB, 1000L, USD));

            assertThatThrownBy(() -> service.createBalanced("lopsided transfer", USD, lopsided))
                    .isInstanceOf(UnbalancedTransactionException.class)
                    .hasMessageContaining("debits=1025")
                    .hasMessageContaining("credits=1000")
                    .hasMessageContaining("difference=25");
        }

        @Test
        @DisplayName("nothing is persisted when the postings do not balance")
        void unbalancedTransactionIsNeverPersisted() {
            List<NewPosting> lopsided = List.of(
                    NewPosting.debit(accountA, 500L, USD),
                    NewPosting.credit(accountB, 499L, USD));

            assertThatThrownBy(() -> service.createBalanced("lopsided transfer", USD, lopsided))
                    .isInstanceOf(UnbalancedTransactionException.class);

            // The invariant is worth nothing if a partial write escapes.
            verifyNoInteractions(transactionRepository);
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("two debits with no credit are rejected")
        void twoDebitsAreRejected() {
            List<NewPosting> bothDebits = List.of(
                    NewPosting.debit(accountA, 1000L, USD),
                    NewPosting.debit(accountB, 1000L, USD));

            assertThatThrownBy(() -> service.createBalanced("both debits", USD, bothDebits))
                    .isInstanceOf(UnbalancedTransactionException.class)
                    .hasMessageContaining("difference=2000");
        }

        @Test
        @DisplayName("legs that balance only when currencies are conflated are rejected")
        void crossCurrencyNettingIsRejected() {
            // 1000 USD debit against 1000 EUR credit sums to zero if you ignore
            // currency. The invariant is per currency, so this must not pass.
            List<NewPosting> crossCurrency = List.of(
                    NewPosting.debit(accountA, 1000L, USD),
                    NewPosting.credit(accountB, 1000L, EUR));

            assertThatThrownBy(() -> service.createBalanced("cross currency", USD, crossCurrency))
                    .isInstanceOf(UnbalancedTransactionException.class)
                    .hasMessageContaining("USD")
                    .hasMessageContaining("EUR");

            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a single posting can never balance")
        void singlePostingIsRejected() {
            assertThatThrownBy(() -> service.createBalanced(
                    "one leg", USD, List.of(NewPosting.debit(accountA, 1000L, USD))))
                    .isInstanceOf(UnbalancedTransactionException.class)
                    .hasMessageContaining("at least two postings");

            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a transaction with no postings is rejected")
        void emptyPostingsAreRejected() {
            assertThatThrownBy(() -> service.createBalanced("nothing", USD, List.of()))
                    .isInstanceOf(UnbalancedTransactionException.class)
                    .hasMessageContaining("no postings");

            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a posting with a non-positive amount is refused outright")
        void nonPositiveAmountIsRefused() {
            // Rejected at construction: direction belongs in the type, not the sign.
            assertThatThrownBy(() -> NewPosting.debit(accountA, 0L, USD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly positive");

            assertThatThrownBy(() -> NewPosting.credit(accountB, -1000L, USD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly positive");
        }
    }

    @Nested
    @DisplayName("accepts postings that balance")
    class Balanced {

        @Test
        @DisplayName("an equal debit and credit pair is persisted")
        void matchedPairIsPersisted() {
            repositorySavesWhateverItIsGiven();

            PostedTransaction posted = service.createBalanced("transfer", USD, List.of(
                    NewPosting.credit(accountA, 1025L, USD),
                    NewPosting.debit(accountB, 1025L, USD)));

            assertThat(posted.postings()).hasSize(2);
            assertThat(posted.postings())
                    .extracting(Posting::signedAmountMinor)
                    .containsExactlyInAnyOrder(-1025L, 1025L);
            assertThat(posted.postings().stream().mapToLong(Posting::signedAmountMinor).sum()).isZero();

            verify(transactionRepository).save(any(Transaction.class));
            verify(entityManager, org.mockito.Mockito.times(2)).persist(any(Posting.class));
        }

        @Test
        @DisplayName("many legs are fine as long as each currency nets to zero")
        void multiLegTransactionIsPersisted() {
            repositorySavesWhateverItIsGiven();

            UUID accountC = UUID.randomUUID();

            assertThatCode(() -> service.createBalanced("split transfer", USD, List.of(
                    NewPosting.credit(accountA, 1000L, USD),
                    NewPosting.debit(accountB, 600L, USD),
                    NewPosting.debit(accountC, 400L, USD))))
                    .doesNotThrowAnyException();

            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("multiple currencies are fine when each one balances independently")
        void perCurrencyBalanceIsEnough() {
            repositorySavesWhateverItIsGiven();

            UUID accountC = UUID.randomUUID();
            UUID accountD = UUID.randomUUID();

            assertThatCode(() -> service.createBalanced("two currencies", USD, List.of(
                    NewPosting.credit(accountA, 1000L, USD),
                    NewPosting.debit(accountB, 1000L, USD),
                    NewPosting.credit(accountC, 250L, EUR),
                    NewPosting.debit(accountD, 250L, EUR))))
                    .doesNotThrowAnyException();

            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("a rejected transaction leaves the repository untouched for the next caller")
        void rejectionDoesNotPersistAnything() {
            assertThatThrownBy(() -> service.createBalanced("bad", USD, List.of(
                    NewPosting.credit(accountA, 10L, USD),
                    NewPosting.debit(accountB, 11L, USD))))
                    .isInstanceOf(UnbalancedTransactionException.class);

            verify(transactionRepository, never()).save(any(Transaction.class));
            verify(entityManager, never()).persist(any());
        }
    }
}
