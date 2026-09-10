package com.ledgerguard.refunds;

import com.ledgerguard.payments.Payment;
import com.ledgerguard.payments.PaymentNotFoundException;
import com.ledgerguard.payments.PaymentRepository;
import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.PostingType;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.Transaction;
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
 * Unit tests for the refund cap and the direction of refund postings.
 *
 * <p>No Spring and no database: the rule that a payment cannot be refunded for
 * more than it was worth is a property of the service, and it should be
 * provable without any persistence machinery, exactly like the Phase 1 balance
 * check.
 */
class RefundServiceTest {

    private static final String USD = "USD";
    private static final long PAID = 1025L;

    private final RefundRepository refunds = mock(RefundRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-02-01T09:00:00Z"), ZoneOffset.UTC);

    private final RefundService service = new RefundService(refunds, payments, transactions, clock);

    private final UUID payerAccount = UUID.randomUUID();
    private final UUID payeeAccount = UUID.randomUUID();
    private Payment payment;

    @BeforeEach
    void setUp() {
        payment = Payment.pending(payerAccount, payeeAccount, PAID, USD, Instant.now(clock));
        payment.markPosted(UUID.randomUUID());
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
    }

    private void transactionServiceAcceptsAnything() {
        when(transactions.createBalanced(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    List<NewPosting> requested = invocation.getArgument(2);
                    Transaction txn = Transaction.create("refund", USD, Instant.now(clock));
                    List<Posting> written = requested.stream()
                            .map(p -> Posting.create(txn.getId(), p.accountId(), p.type(),
                                    p.amountMinor(), p.currency(), Instant.now(clock)))
                            .toList();
                    return new PostedTransaction(txn, written);
                });
        when(refunds.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("a refund larger than the payment is rejected")
    void refundLargerThanPaymentIsRejected() {
        when(refunds.totalRefundedMinorUnits(payment.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.refund(payment.getId(), 2000L, "too much"))
                .isInstanceOf(RefundAmountExceededException.class)
                .hasMessageContaining("requested 2000")
                .hasMessageContaining("only 1025 remain refundable");

        verifyNoInteractions(transactions);
        verify(refunds, never()).save(any());
    }

    @Test
    @DisplayName("a refund is rejected once earlier refunds have used up the amount")
    void refundBeyondRemainderIsRejected() {
        // 600 of 1025 already refunded, so only 425 remain.
        when(refunds.totalRefundedMinorUnits(payment.getId())).thenReturn(600L);

        assertThatThrownBy(() -> service.refund(payment.getId(), 426L, "one too many"))
                .isInstanceOf(RefundAmountExceededException.class)
                .hasMessageContaining("only 425 remain refundable")
                .hasMessageContaining("already refunded 600");

        verifyNoInteractions(transactions);
        verify(refunds, never()).save(any());
    }

    @Test
    @DisplayName("refunding exactly the remaining amount is allowed")
    void refundOfExactRemainderIsAccepted() {
        when(refunds.totalRefundedMinorUnits(payment.getId())).thenReturn(600L);
        transactionServiceAcceptsAnything();

        var response = service.refund(payment.getId(), 425L, "the rest");

        assertThat(response.amountMinorUnits()).isEqualTo(425L);
        assertThat(response.refundedTotalMinorUnits()).isEqualTo(1025L);
        assertThat(response.remainingRefundableMinorUnits()).isZero();
        verify(refunds).save(any(Refund.class));
    }

    @Test
    @DisplayName("refund postings run the payment backwards")
    void refundPostingsReverseTheOriginalDirection() {
        when(refunds.totalRefundedMinorUnits(payment.getId())).thenReturn(0L);
        transactionServiceAcceptsAnything();

        service.refund(payment.getId(), 400L, "partial");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewPosting>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactions).createBalanced(anyString(), anyString(), captor.capture());

        List<NewPosting> legs = captor.getValue();
        assertThat(legs).hasSize(2);

        // The payment CREDITed the payer and DEBITed the payee. The refund does
        // the opposite: the payer is made whole, the payee gives it back.
        NewPosting toPayer = legs.stream().filter(p -> p.accountId().equals(payerAccount)).findFirst().orElseThrow();
        NewPosting fromPayee = legs.stream().filter(p -> p.accountId().equals(payeeAccount)).findFirst().orElseThrow();

        assertThat(toPayer.type()).isEqualTo(PostingType.DEBIT);
        assertThat(fromPayee.type()).isEqualTo(PostingType.CREDIT);
        assertThat(toPayer.amountMinor()).isEqualTo(400L);
        assertThat(fromPayee.amountMinor()).isEqualTo(400L);
        assertThat(legs.stream().mapToLong(NewPosting::signedAmountMinor).sum())
                .as("a refund must balance on its own")
                .isZero();
    }

    @Test
    @DisplayName("a zero or negative refund is refused")
    void nonPositiveRefundIsRefused() {
        assertThatThrownBy(() -> service.refund(payment.getId(), 0L, "nothing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");

        assertThatThrownBy(() -> service.refund(payment.getId(), -50L, "negative"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactions);
    }

    @Test
    @DisplayName("refunding an unknown payment is a not-found, not a crash")
    void unknownPaymentIsRejected() {
        UUID missing = UUID.randomUUID();
        when(payments.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(missing, 100L, "nope"))
                .isInstanceOf(PaymentNotFoundException.class);

        verifyNoInteractions(transactions);
    }
}
