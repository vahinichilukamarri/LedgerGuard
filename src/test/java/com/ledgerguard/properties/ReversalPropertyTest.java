package com.ledgerguard.properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.config.Money;
import com.ledgerguard.reversals.TransactionAlreadyReversedException;
import com.ledgerguard.support.PropertyLedger;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reversals: exact negation, and exactly once.
 *
 * <p>Two properties are bundled together here because they are two halves of the
 * same guarantee. A reversal that is not an exact negation leaves money behind;
 * a reversal that can happen twice takes money that was never there. Both would
 * still leave every individual transaction balanced, so the per-transaction
 * invariant alone does not catch either one.
 *
 * <h2>Tries</h2>
 *
 * 80 per property. A try posts a payment, reverses it, then attempts several
 * further reversals — around fifteen database round trips. The generator forces
 * the single-minor-unit case, where an exact negation is least forgiving.
 */
@Tag("property")
class ReversalPropertyTest {

    /**
     * A transaction and its reversal net to zero together, per account and per
     * currency — which for a payment means both parties are exactly back where
     * they started.
     */
    @Property(tries = 80)
    void aTransactionAndItsReversalNetToZero(@ForAll("supported") String currency,
                                             @ForAll("amounts") long amountMinor) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(amountMinor, currency), currency);
        UUID originalTransaction = PropertyLedger.transactionIdOf(payment);

        assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isEqualTo(-amountMinor);

        JsonNode reversal = PropertyLedger.reverse(originalTransaction);
        UUID reversalTransaction = PropertyLedger.reversalTransactionIdOf(reversal);

        assertThat(PropertyLedger.balanceMinorUnits(payer, currency))
                .as("the payer is exactly back where they started")
                .isZero();
        assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isZero();

        // The original was not edited: it still stands, with its two legs.
        assertThat(PropertyLedger.postingCountFor(originalTransaction)).isEqualTo(2L);
        assertThat(PropertyLedger.transactionNetMinorUnits(originalTransaction)).isZero();

        // And the reversal is a balanced transaction in its own right.
        assertThat(PropertyLedger.postingCountFor(reversalTransaction)).isEqualTo(2L);
        assertThat(PropertyLedger.transactionNetMinorUnits(reversalTransaction)).isZero();
        assertThat(PropertyLedger.unbalancedTransactionCurrencyPairs()).isEmpty();
    }

    /**
     * However many times a reversal is attempted with distinct keys, exactly one
     * succeeds and the rest change nothing.
     */
    @Property(tries = 80)
    void aTransactionCanOnlyEverBeReversedOnce(@ForAll("supported") String currency,
                                               @ForAll("amounts") long amountMinor,
                                               @ForAll @IntRange(min = 1, max = 5) int extraAttempts) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(amountMinor, currency), currency);
        UUID originalTransaction = PropertyLedger.transactionIdOf(payment);

        PropertyLedger.reverse(originalTransaction);
        long postingsAfterReversal = PropertyLedger.postingCount();

        for (int i = 0; i < extraAttempts; i++) {
            // A fresh key each time, so this is a genuine second reversal
            // attempt rather than an idempotent replay of the first one.
            assertThatThrownBy(() -> PropertyLedger.reverse(originalTransaction))
                    .isInstanceOf(TransactionAlreadyReversedException.class);

            assertThat(PropertyLedger.reversalCountFor(originalTransaction)).isEqualTo(1L);
            assertThat(PropertyLedger.postingCount()).isEqualTo(postingsAfterReversal);
            assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isZero();
            assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isZero();
        }
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
    }

    /**
     * A refund's transaction is a transaction like any other, so it can be
     * reversed too — and when it is, the refund's movement is undone exactly,
     * leaving the payer owing the original amount again.
     */
    @Property(tries = 80)
    void reversingARefundUndoesExactlyTheRefund(@ForAll("supported") String currency,
                                                @ForAll("refundableAmounts") long amountMinor) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(amountMinor, currency), currency);
        UUID paymentId = PropertyLedger.paymentIdOf(payment);

        long refundAmount = Math.max(1L, amountMinor / 2);
        JsonNode refund = PropertyLedger.refund(
                paymentId, Money.toMajorUnits(refundAmount, currency));
        UUID refundTransaction = PropertyLedger.transactionIdOf(refund);

        assertThat(PropertyLedger.balanceMinorUnits(payer, currency))
                .isEqualTo(-(amountMinor - refundAmount));

        PropertyLedger.reverse(refundTransaction);

        assertThat(PropertyLedger.balanceMinorUnits(payer, currency))
                .as("with the refund reversed, the payer is out of pocket the full amount again")
                .isEqualTo(-amountMinor);
        assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isEqualTo(amountMinor);
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
        assertThat(PropertyLedger.unbalancedTransactionCurrencyPairs()).isEmpty();
    }

    /**
     * A reversal is itself reversible — nothing marks it special — and doing so
     * reinstates the original movement exactly rather than compounding it.
     *
     * <p>This is deliberately asserted as allowed rather than forbidden. A
     * reversal is just a transaction, and the general rule is that any
     * transaction may be reversed once; what matters is that the arithmetic
     * stays exact however deep the chain goes.
     */
    @Property(tries = 80)
    void reversingAReversalReinstatesTheOriginalExactly(@ForAll("supported") String currency,
                                                        @ForAll("amounts") long amountMinor) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(amountMinor, currency), currency);
        UUID originalTransaction = PropertyLedger.transactionIdOf(payment);

        JsonNode reversal = PropertyLedger.reverse(originalTransaction);
        UUID reversalTransaction = PropertyLedger.reversalTransactionIdOf(reversal);
        assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isZero();

        PropertyLedger.reverse(reversalTransaction);

        assertThat(PropertyLedger.balanceMinorUnits(payer, currency))
                .as("undoing the undo restores the original movement, not twice it")
                .isEqualTo(-amountMinor);
        assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isEqualTo(amountMinor);
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
        assertThat(PropertyLedger.unbalancedTransactionCurrencyPairs()).isEmpty();
    }

    /**
     * The same chain taken to an arbitrary depth: payment, reversal, reversal of
     * that reversal, and so on.
     *
     * <p>The previous property fixes the depth at two because that is the case
     * worth naming. This one generates it, and asserts the two things that have
     * to hold at <em>every</em> step rather than only at the end:
     *
     * <ul>
     *   <li><b>conservation</b> — the payer's balance never rises above zero and
     *       the pair always nets to zero, so no depth of reversal chain can leave
     *       the payer better off than before they paid;</li>
     *   <li><b>exact alternation</b> — after an odd number of reversals the payer
     *       is whole, after an even number they are out of pocket the original
     *       amount, and never any other value. Drift of a single minor unit at
     *       depth five would satisfy conservation and still be a bug.</li>
     * </ul>
     *
     * <p>Every transaction in such a chain balances on its own no matter what,
     * which is exactly why that is not the property being stated here.
     */
    @Property(tries = 80)
    void reversalChainsConserveMoneyAtAnyDepth(@ForAll("supported") String currency,
                                               @ForAll("amounts") long amountMinor,
                                               @ForAll @IntRange(min = 1, max = 6) int depth) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(amountMinor, currency), currency);
        UUID paymentId = PropertyLedger.paymentIdOf(payment);

        // Each iteration reverses whatever the previous one produced.
        UUID target = PropertyLedger.transactionIdOf(payment);

        for (int reversals = 1; reversals <= depth; reversals++) {
            JsonNode reversal = PropertyLedger.reverse(target);
            target = PropertyLedger.reversalTransactionIdOf(reversal);

            long payerBalance = PropertyLedger.balanceMinorUnits(payer, currency);
            long payeeBalance = PropertyLedger.balanceMinorUnits(payee, currency);

            assertThat(payerBalance + payeeBalance)
                    .as("after %d reversals the pair must still net to zero", reversals)
                    .isZero();
            assertThat(payerBalance)
                    .as("after %d reversals the payer must not be better off than before paying",
                            reversals)
                    .isLessThanOrEqualTo(0L);

            long expected = reversals % 2 == 1 ? 0L : -amountMinor;
            assertThat(payerBalance)
                    .as("after %d reversals of %d %s the payer must be exactly %d",
                            reversals, amountMinor, currency, expected)
                    .isEqualTo(expected);

            assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
            assertThat(PropertyLedger.unbalancedTransactionCurrencyPairs()).isEmpty();
        }

        // The payment was marked REVERSED by the first reversal and stays that
        // way however deep the chain goes, so it can never be refunded on top.
        // That is conservative even at even depths, where the money really has
        // moved to the payee again — it under-refunds rather than over-refunds.
        assertThatThrownBy(() -> PropertyLedger.refund(
                paymentId, Money.toMajorUnits(1L, currency)))
                .as("a reversed payment must stay unrefundable at every depth")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PropertyLedger.refundCountFor(paymentId)).isZero();
    }

    // ------------------------------------------------------------- generators

    @Provide
    Arbitrary<String> supported() {
        return LedgerArbitraries.supportedCurrencies();
    }

    @Provide
    Arbitrary<Long> amounts() {
        return LedgerArbitraries.chainAmountsMinor();
    }

    @Provide
    Arbitrary<Long> refundableAmounts() {
        return LedgerArbitraries.refundableAmountsMinor();
    }
}
