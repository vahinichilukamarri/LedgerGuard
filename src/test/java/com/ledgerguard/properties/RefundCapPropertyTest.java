package com.ledgerguard.properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.config.Money;
import com.ledgerguard.properties.LedgerArbitraries.RefundPlan;
import com.ledgerguard.refunds.RefundAmountExceededException;
import com.ledgerguard.support.PropertyLedger;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refund cap, across arbitrary sequences of partial refunds.
 *
 * <p>{@code RefundServiceTest} checks six hand-picked cases. What it cannot
 * check is that the cap survives an <em>arbitrary</em> chain of partials —
 * particularly the last one, which has to land exactly on the remainder and is
 * where an off-by-one would hide. These properties generate the whole sequence
 * and assert the cap after every step.
 *
 * <h2>Tries</h2>
 *
 * 100 per property. Each try creates two accounts, posts a payment, and then
 * performs up to six refunds — roughly a dozen database round trips, some of
 * them taking a row lock. The generator forces the small amounts (2, 3, 5 minor
 * units) as edge cases, because a payment of two minor units split into two
 * refunds is the sharpest test of the cap and is vanishingly unlikely to be
 * generated at random from a range that runs to ten million.
 */
@Tag("property")
class RefundCapPropertyTest {

    /**
     * Any sequence of partial refunds that fits, fits — and the one that does
     * not is refused, leaving the ledger where it was.
     */
    @Property(tries = 100)
    void partialRefundsNeverExceedTheRefundableAmount(@ForAll("plans") RefundPlan plan) {
        String currency = plan.currency();
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(plan.paymentMinor(), currency), currency);
        UUID paymentId = PropertyLedger.paymentIdOf(payment);

        long refundedSoFar = 0;
        for (long split : plan.splits()) {
            JsonNode refund = PropertyLedger.refund(
                    paymentId, Money.toMajorUnits(split, currency));
            refundedSoFar += split;

            assertThat(refund.get("refundedTotalMinorUnits").asLong())
                    .as("the running refunded total is derived from the refund rows")
                    .isEqualTo(refundedSoFar);
            assertThat(refund.get("remainingRefundableMinorUnits").asLong())
                    .isEqualTo(plan.paymentMinor() - refundedSoFar);

            // The cap holds after every single step, not just at the end.
            assertThat(PropertyLedger.refundedTotalFor(paymentId))
                    .as("cumulative refunds may never exceed the payment")
                    .isLessThanOrEqualTo(plan.paymentMinor());
            assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
        }

        assertThat(PropertyLedger.refundedTotalFor(paymentId)).isEqualTo(plan.refundedTotal());
        assertThat(PropertyLedger.refundCountFor(paymentId)).isEqualTo(plan.splits().size());

        // Whatever is left unrefunded is exactly what the payer is still out of pocket.
        long stillOwed = plan.paymentMinor() - plan.refundedTotal();
        assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isEqualTo(-stillOwed);
        assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isEqualTo(stillOwed);
    }

    /**
     * One minor unit past the remaining refundable is always refused, and
     * refusing it writes nothing.
     *
     * <p>Stated at exactly +1 rather than "some larger number" on purpose: a cap
     * implemented with {@code >=} instead of {@code >} would still refuse a
     * wildly excessive refund and would only be caught here.
     */
    @Property(tries = 100)
    void oneMinorUnitPastTheCapIsAlwaysRefused(@ForAll("plans") RefundPlan plan) {
        String currency = plan.currency();
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(plan.paymentMinor(), currency), currency);
        UUID paymentId = PropertyLedger.paymentIdOf(payment);

        // Refund all but the last split, so there is a known remainder.
        List<Long> splits = plan.splits();
        long refunded = 0;
        for (int i = 0; i < splits.size() - 1; i++) {
            PropertyLedger.refund(paymentId, Money.toMajorUnits(splits.get(i), currency));
            refunded += splits.get(i);
        }

        long remaining = plan.paymentMinor() - refunded;
        long postingsBefore = PropertyLedger.postingCount();
        long refundsBefore = PropertyLedger.refundCountFor(paymentId);

        assertThatThrownBy(() -> PropertyLedger.refund(
                paymentId, Money.toMajorUnits(remaining + 1, currency)))
                .as("refunding %d against a remaining %d must be refused", remaining + 1, remaining)
                .isInstanceOf(RefundAmountExceededException.class);

        assertThat(PropertyLedger.postingCount()).isEqualTo(postingsBefore);
        assertThat(PropertyLedger.refundCountFor(paymentId)).isEqualTo(refundsBefore);
        assertThat(PropertyLedger.refundedTotalFor(paymentId)).isEqualTo(refunded);

        // And the exact remainder is still accepted afterwards: a refused
        // attempt must not consume any of the refundable amount.
        PropertyLedger.refund(paymentId, Money.toMajorUnits(remaining, currency));
        assertThat(PropertyLedger.refundedTotalFor(paymentId)).isEqualTo(plan.paymentMinor());
        assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isZero();
        assertThat(PropertyLedger.balanceMinorUnits(payee, currency)).isZero();
    }

    /**
     * The biconditional, over a sequence of arbitrary amounts rather than a
     * plan that was built to fit: a refund is accepted <em>if and only if</em>
     * it is within what remains, whatever order the attempts arrive in.
     */
    @Property(tries = 100)
    void aRefundIsAcceptedExactlyWhenItFitsWithinWhatRemains(
            @ForAll("supported") String currency,
            @ForAll("paymentAmounts") long paymentMinor,
            @ForAll("attemptPercents") List<Integer> attemptPercents) {

        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(
                payer, payee, Money.toMajorUnits(paymentMinor, currency), currency);
        UUID paymentId = PropertyLedger.paymentIdOf(payment);

        long refunded = 0;
        List<String> history = new ArrayList<>();

        for (int percent : attemptPercents) {
            long attempt = Math.max(1L, paymentMinor * percent / 100);
            long remaining = paymentMinor - refunded;
            boolean shouldFit = attempt <= remaining;
            history.add(attempt + (shouldFit ? " (fits)" : " (over " + remaining + ")"));

            BigDecimal asDecimal = Money.toMajorUnits(attempt, currency);
            if (shouldFit) {
                PropertyLedger.refund(paymentId, asDecimal);
                refunded += attempt;
            } else {
                assertThatThrownBy(() -> PropertyLedger.refund(paymentId, asDecimal))
                        .as("attempt sequence %s", history)
                        .isInstanceOf(RefundAmountExceededException.class);
            }

            assertThat(PropertyLedger.refundedTotalFor(paymentId))
                    .as("attempt sequence %s", history)
                    .isEqualTo(refunded)
                    .isLessThanOrEqualTo(paymentMinor);
        }

        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
        assertThat(PropertyLedger.unbalancedTransactionCurrencyPairs()).isEmpty();
    }

    // ------------------------------------------------------------- generators

    @Provide
    Arbitrary<RefundPlan> plans() {
        return LedgerArbitraries.refundPlans();
    }

    @Provide
    Arbitrary<String> supported() {
        return LedgerArbitraries.supportedCurrencies();
    }

    @Provide
    Arbitrary<Long> paymentAmounts() {
        return LedgerArbitraries.refundableAmountsMinor();
    }

    @Provide
    Arbitrary<List<Integer>> attemptPercents() {
        return LedgerArbitraries.refundPercents();
    }
}
