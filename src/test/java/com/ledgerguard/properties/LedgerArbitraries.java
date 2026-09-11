package com.ledgerguard.properties;

import com.ledgerguard.config.Money;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import java.math.BigDecimal;
import java.util.List;

/**
 * The generators every property in this package draws from.
 *
 * <p>Kept in one class on purpose. A property is only as good as the inputs it
 * sees, so what those inputs are — and which edge cases are deliberately forced
 * rather than left to chance — is the part worth reading, and it should be
 * readable in one place rather than scattered across seven test classes.
 *
 * <p>Two conventions here:
 * <ul>
 *   <li>Money is generated as a count of <em>minor units</em>, because that is
 *       what the ledger stores. Decimal amounts for the API boundary are derived
 *       from it with {@link Money#toMajorUnits}, so a generated case is always
 *       exactly representable in its currency unless the generator's whole point
 *       is that it should not be.</li>
 *   <li>Currency lists are not "a few currencies". They are chosen so every
 *       distinct minor-unit shape the JDK knows about is represented: 0, 2 and 3
 *       decimal places, plus codes with no minor unit at all.</li>
 * </ul>
 */
public final class LedgerArbitraries {

    private LedgerArbitraries() {
    }

    /** Two decimal places — the ordinary case. */
    public static final List<String> TWO_DECIMAL = List.of("USD", "EUR", "GBP", "CHF");

    /** Zero decimal places. 100 JPY is 100 minor units, not 10000. */
    public static final List<String> ZERO_DECIMAL = List.of("JPY", "KRW", "VND", "CLP");

    /** Three decimal places. The other direction from JPY, and just as real. */
    public static final List<String> THREE_DECIMAL = List.of("KWD", "BHD", "JOD", "TND", "OMR");

    /**
     * Codes that must always be refused, covering all three ways a currency can
     * be unusable: malformed, well-formed but unknown to ISO-4217, and known but
     * with no minor unit defined (the precious metals and XXX, which the JDK
     * reports as -1 fraction digits).
     */
    public static final List<String> UNUSABLE = List.of(
            "US", "USDD", "1AB", "US1", "", "u$d", "  ", "usd usd",   // malformed
            "ZZZ", "QQQ", "AAB", "ABC",                               // well-formed, unknown
            "XAU", "XPT", "XAG", "XDR", "XXX");                       // known, no minor unit

    /**
     * The amount ceiling for properties that build multi-step chains.
     *
     * <p>10^12 minor units is ten billion dollars — far past any realistic
     * payment, and far enough below {@link Long#MAX_VALUE} that a chain of six
     * operations against one account cannot make the derived balance aggregate
     * exceed a {@code long}. The genuine overflow boundary is not skipped by
     * this; it is probed deliberately and in isolation by
     * {@link MoneyPropertyTest}, where a failure means something specific
     * instead of poisoning an unrelated property.
     */
    public static final long CHAIN_AMOUNT_CEILING = 1_000_000_000_000L;

    // ------------------------------------------------------------ currencies

    /** Every supported currency, weighted so the unusual minor units are not rare. */
    public static Arbitrary<String> supportedCurrencies() {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, Arbitraries.of(TWO_DECIMAL)),
                net.jqwik.api.Tuple.of(3, Arbitraries.of(ZERO_DECIMAL)),
                net.jqwik.api.Tuple.of(3, Arbitraries.of(THREE_DECIMAL)));
    }

    public static Arbitrary<String> zeroDecimalCurrencies() {
        return Arbitraries.of(ZERO_DECIMAL);
    }

    public static Arbitrary<String> unusableCurrencies() {
        return Arbitraries.of(UNUSABLE);
    }

    /**
     * A supported code in random letter case. Case is not supposed to matter —
     * {@code Money.normalize} upper-cases it — so a property that holds for
     * "usd" but not "UsD" is a bug.
     */
    public static Arbitrary<String> mixedCaseCurrencies() {
        return supportedCurrencies().flatMap(code ->
                Arbitraries.integers().between(0, (1 << code.length()) - 1)
                        .map(mask -> applyCaseMask(code, mask)));
    }

    private static String applyCaseMask(String code, int mask) {
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            out.append(((mask >> i) & 1) == 1 ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return out.toString();
    }

    // --------------------------------------------------------------- amounts

    /**
     * Amounts for chain properties. Edge cases pin the small end explicitly: a
     * single minor unit is where off-by-one and rounding bugs live, and random
     * sampling over twelve orders of magnitude would essentially never produce
     * it.
     */
    public static Arbitrary<Long> chainAmountsMinor() {
        return Arbitraries.longs().between(1L, CHAIN_AMOUNT_CEILING)
                .edgeCases(config -> config.add(1L, 2L, 3L, 99L, 100L, 101L, 999L, 1000L,
                        (long) Integer.MAX_VALUE, CHAIN_AMOUNT_CEILING));
    }

    /** Small amounts, for chains that need room to split into several partial refunds. */
    public static Arbitrary<Long> refundableAmountsMinor() {
        return Arbitraries.longs().between(2L, 10_000_000L)
                .edgeCases(config -> config.add(2L, 3L, 5L, 100L, 101L));
    }

    /** The actual boundary of the representation, probed on purpose. */
    public static Arbitrary<Long> boundaryAmountsMinor() {
        return Arbitraries.of(
                1L, 2L, 9L, 10L, 99L, 100L,
                (long) Integer.MAX_VALUE, Integer.MAX_VALUE + 1L,
                Long.MAX_VALUE / 2, Long.MAX_VALUE - 2, Long.MAX_VALUE - 1, Long.MAX_VALUE);
    }

    /**
     * A decimal carrying exactly one more fraction digit than its currency has
     * minor units, with a non-zero final digit so the extra precision is real.
     *
     * <p>This is the case that must be <em>rejected</em>, never rounded: a
     * silently dropped tenth of a cent is how a ledger stops balancing.
     */
    public static Arbitrary<SubMinorAmount> subMinorAmounts() {
        return Combinators.combine(
                        supportedCurrencies(),
                        Arbitraries.longs().between(0L, 100_000L),
                        Arbitraries.integers().between(1, 9))
                .as((currency, whole, extraDigit) -> {
                    int digits = Money.fractionDigits(currency);
                    BigDecimal amount = BigDecimal.valueOf(whole * 10L + extraDigit, digits + 1);
                    return new SubMinorAmount(currency, amount, digits);
                });
    }

    /** A decimal amount that is finer than its currency can express. */
    public record SubMinorAmount(String currency, BigDecimal amount, int currencyFractionDigits) {
    }

    // ------------------------------------------------------- refund sequences

    /**
     * A payment amount together with a plan for chopping it into partial
     * refunds, plus one deliberate overshoot at the end.
     *
     * <p>The splits are generated as weights and normalised against the payment
     * amount, so the sequence always sums to at most the payment and the last
     * partial lands exactly on the remainder. That matters: "the final partial
     * refund is accepted to the last minor unit" is a case a naive random
     * generator would miss, and it is precisely where an off-by-one in the cap
     * would show up.
     */
    public static Arbitrary<RefundPlan> refundPlans() {
        return Combinators.combine(
                        refundableAmountsMinor(),
                        supportedCurrencies(),
                        Arbitraries.integers().between(1, 20).list().ofMinSize(1).ofMaxSize(6),
                        Arbitraries.longs().between(1L, 1_000L))
                .as(RefundPlan::of);
    }

    /**
     * @param paymentMinor  what the payment was for
     * @param currency      its currency
     * @param splits        successive partial refunds, summing to at most paymentMinor
     * @param overshootBy   how far past the remaining refundable the final attempt reaches
     */
    public record RefundPlan(long paymentMinor, String currency, List<Long> splits, long overshootBy) {

        static RefundPlan of(long paymentMinor, String currency, List<Integer> weights, long overshootBy) {
            long totalWeight = weights.stream().mapToLong(Integer::longValue).sum();
            List<Long> splits = new java.util.ArrayList<>();
            long remaining = paymentMinor;
            long weightLeft = totalWeight;

            for (int i = 0; i < weights.size() && remaining > 0; i++) {
                long weight = weights.get(i);
                boolean last = i == weights.size() - 1;
                long share = last ? remaining : Math.max(1L, paymentMinor * weight / totalWeight);
                share = Math.min(share, remaining);
                if (share <= 0) {
                    break;
                }
                splits.add(share);
                remaining -= share;
                weightLeft -= weight;
                if (weightLeft <= 0) {
                    break;
                }
            }
            if (splits.isEmpty()) {
                splits.add(paymentMinor);
            }
            return new RefundPlan(paymentMinor, currency, List.copyOf(splits), overshootBy);
        }

        public long refundedTotal() {
            return splits.stream().mapToLong(Long::longValue).sum();
        }
    }

    // ---------------------------------------------------- operation sequences

    /**
     * One step in a generated transaction chain.
     *
     * <p>The alphabet includes steps that are supposed to fail. That is the
     * point: a sequence like REVERSE_PAYMENT then REVERSE_PAYMENT again, or
     * REFUND past the cap, must be refused and must leave nothing behind, and a
     * generator that only produced legal sequences would never check it.
     */
    public enum Step {
        /** Refund a generated fraction of the payment. May legitimately exceed the cap. */
        REFUND,
        /** Reverse the payment's own transaction. Legal at most once. */
        REVERSE_PAYMENT,
        /** Reverse the most recent refund's transaction. Legal at most once each. */
        REVERSE_LAST_REFUND,
        /**
         * Reverse the transaction the most recent reversal <em>produced</em>.
         *
         * <p>This is what takes a chain past depth two. A reversal is an ordinary
         * transaction, so it is itself reversible, and reversing it reinstates the
         * movement the first reversal undid. Without this step the alphabet can
         * never reach a reversal-of-a-reversal at all: REVERSE_PAYMENT always
         * targets the payment's own transaction and a second attempt is simply
         * refused.
         */
        REVERSE_LAST_REVERSAL,
        /** Re-send the previous operation under its original idempotency key. */
        REPLAY_LAST
    }

    public static Arbitrary<List<Step>> operationChains() {
        return Arbitraries.of(Step.class).list().ofMinSize(1).ofMaxSize(6);
    }

    /** Fractions, in percent, used to size the REFUND steps of a chain. */
    public static Arbitrary<List<Integer>> refundPercents() {
        return Arbitraries.integers().between(1, 130).list().ofMinSize(1).ofMaxSize(6);
    }

    // --------------------------------------------------------- posting shapes

    /**
     * A description of a set of postings across one to three currencies, where
     * each currency is independently either balanced or skewed.
     *
     * <p>Generating the <em>skew</em> rather than the postings directly is what
     * makes the property two-sided: the same generator produces sets that must
     * be accepted and sets that must be rejected, and the property asserts the
     * biconditional rather than only checking that good input works.
     */
    public record CurrencyLeg(String currency, long amountMinor, long skewMinor, int extraPairs) {
        public boolean balanced() {
            return skewMinor == 0;
        }
    }

    public static Arbitrary<List<CurrencyLeg>> currencyLegs() {
        return Combinators.combine(
                        supportedCurrencies(),
                        Arbitraries.longs().between(1L, 1_000_000_000L),
                        Arbitraries.frequency(
                                net.jqwik.api.Tuple.of(3, 0L),
                                net.jqwik.api.Tuple.of(1, 1L),
                                net.jqwik.api.Tuple.of(1, -1L),
                                net.jqwik.api.Tuple.of(1, 1_000L),
                                net.jqwik.api.Tuple.of(1, -1_000L)),
                        Arbitraries.integers().between(0, 3))
                .as(CurrencyLeg::new)
                .list().ofMinSize(1).ofMaxSize(3)
                .uniqueElements(CurrencyLeg::currency);
    }
}
