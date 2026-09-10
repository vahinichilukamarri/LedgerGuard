package com.ledgerguard.transactions;

import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.properties.LedgerArbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Properties of the balance check itself, with no database in the way.
 *
 * <p>{@code TransactionService.requireBalanced} is a pure function over a list
 * of postings, deliberately kept free of persistence concerns, which means it
 * can be hit with jqwik's full default of 1000 tries per property at essentially
 * no cost. The example-based {@code TransactionServiceBalanceTest} checks eleven
 * hand-chosen shapes; these check the same rule over arbitrary ones.
 *
 * <p>The properties here are stated as <em>biconditionals</em> — accepted if and
 * only if balanced — rather than as "balanced input is accepted". A check that
 * accepted everything would satisfy the weaker form.
 */
@Tag("property")
class BalanceInvariantPropertyTest {

    /**
     * No dependencies are touched by {@code requireBalanced}, so nulls are safe
     * here and keep the property free of a Spring context.
     */
    private final TransactionService service = new TransactionService(null, null, null);

    @Property(tries = 1000)
    void acceptedIfAndOnlyIfEveryCurrencyNetsToZero(@ForAll("legs") List<LedgerArbitraries.CurrencyLeg> legs) {
        List<NewPosting> postings = postingsFor(legs);
        boolean everyCurrencyBalances = legs.stream().allMatch(BalanceInvariantPropertyTest::legBalances);

        if (everyCurrencyBalances) {
            assertThatCode(() -> service.requireBalanced(postings))
                    .as("every currency nets to zero, so this must be accepted: %s", postings)
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> service.requireBalanced(postings))
                    .as("at least one currency does not net to zero: %s", postings)
                    .isInstanceOf(UnbalancedTransactionException.class);
        }
    }

    @Property(tries = 1000)
    void orderOfPostingsNeverChangesTheVerdict(@ForAll("legs") List<LedgerArbitraries.CurrencyLeg> legs,
                                               @ForAll @IntRange(min = 0, max = 10_000) int seed) {
        List<NewPosting> postings = postingsFor(legs);
        List<NewPosting> shuffled = new ArrayList<>(postings);
        Collections.shuffle(shuffled, new Random(seed));

        assertThat(verdict(postings))
                .as("the invariant is a property of the set, not of the order it arrives in")
                .isEqualTo(verdict(shuffled));
    }

    /**
     * The currency-isolation property, stated at its sharpest.
     *
     * <p>Each currency is given a non-zero net, and the nets are constructed to
     * cancel each other exactly. Summing the amounts without regard to currency
     * gives zero; a correct check still rejects the whole thing.
     */
    @Property(tries = 1000)
    void currenciesNeverNetAgainstEachOther(@ForAll("twoDistinctCurrencies") List<String> currencies,
                                            @ForAll @LongRange(min = 1L, max = 1_000_000_000L) long amount,
                                            @ForAll @LongRange(min = 1L, max = 1_000_000L) long skew) {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        String first = currencies.get(0);
        String second = currencies.get(1);

        // First currency is long by `skew`, second is short by exactly the same
        // number. Ignore the currency column and the two cancel; respect it and
        // neither one balances.
        List<NewPosting> postings = List.of(
                NewPosting.debit(accountA, amount + skew, first),
                NewPosting.credit(accountB, amount, first),
                NewPosting.debit(accountA, amount, second),
                NewPosting.credit(accountB, amount + skew, second));

        long netIgnoringCurrency = postings.stream().mapToLong(NewPosting::signedAmountMinor).sum();
        assertThat(netIgnoringCurrency)
                .as("the trap is only a trap if the currency-blind sum really is zero")
                .isZero();

        assertThatThrownBy(() -> service.requireBalanced(postings))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    /**
     * Adding balanced pairs in a currency that was already balanced cannot turn
     * a good transaction bad, however many are added and in whatever currency.
     */
    @Property(tries = 1000)
    void balancedPairsCanBeAddedFreely(@ForAll("supported") String currency,
                                       @ForAll @LongRange(min = 1L, max = 1_000_000_000L) long amount,
                                       @ForAll("supported") String extraCurrency,
                                       @ForAll @LongRange(min = 1L, max = 1_000_000_000L) long extraAmount,
                                       @ForAll @IntRange(min = 0, max = 5) int extraPairs) {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        List<NewPosting> postings = new ArrayList<>(List.of(
                NewPosting.debit(accountA, amount, currency),
                NewPosting.credit(accountB, amount, currency)));

        for (int i = 0; i < extraPairs; i++) {
            postings.add(NewPosting.debit(accountB, extraAmount, extraCurrency));
            postings.add(NewPosting.credit(accountA, extraAmount, extraCurrency));
        }

        assertThatCode(() -> service.requireBalanced(postings)).doesNotThrowAnyException();
    }

    /** A transaction with fewer than two postings can never balance and is always refused. */
    @Property(tries = 1000)
    void aSinglePostingIsNeverATransaction(@ForAll("supported") String currency,
                                           @ForAll @LongRange(min = 1L, max = Long.MAX_VALUE) long amount) {
        List<NewPosting> lone = List.of(NewPosting.debit(UUID.randomUUID(), amount, currency));

        assertThatThrownBy(() -> service.requireBalanced(lone))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    /**
     * Sums are accumulated with {@code Math.addExact}, so a set whose debits
     * overflow a {@code long} fails loudly instead of wrapping around into a
     * total that happens to look balanced.
     */
    @Property(tries = 1000)
    void overflowingSumsFailLoudlyRatherThanWrapping(
            @ForAll("supported") String currency,
            @ForAll @LongRange(min = Long.MAX_VALUE / 2 + 1, max = Long.MAX_VALUE) long half) {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        // Two debits of `half` overflow; the matching credits make the wrapped
        // arithmetic net to zero, which is exactly the false pass being guarded.
        List<NewPosting> postings = List.of(
                NewPosting.debit(accountA, half, currency),
                NewPosting.debit(accountA, half, currency),
                NewPosting.credit(accountB, half, currency),
                NewPosting.credit(accountB, half, currency));

        assertThatThrownBy(() -> service.requireBalanced(postings))
                .as("an overflowed total must never be reported as balanced")
                .isInstanceOf(ArithmeticException.class);
    }

    // ------------------------------------------------------------- generators

    @Provide
    Arbitrary<List<LedgerArbitraries.CurrencyLeg>> legs() {
        return LedgerArbitraries.currencyLegs();
    }

    @Provide
    Arbitrary<String> supported() {
        return LedgerArbitraries.supportedCurrencies();
    }

    @Provide
    Arbitrary<List<String>> twoDistinctCurrencies() {
        return LedgerArbitraries.supportedCurrencies().list().ofSize(2).uniqueElements();
    }

    // ---------------------------------------------------------------- helpers

    private static boolean legBalances(LedgerArbitraries.CurrencyLeg leg) {
        return creditAmount(leg) == leg.amountMinor();
    }

    /**
     * Skew is applied to the credit side and floored at one minor unit, because
     * a posting amount is always strictly positive. Flooring can cancel the skew
     * out, which is fine — {@link #legBalances} reads the amounts back rather
     * than trusting the requested skew.
     */
    private static long creditAmount(LedgerArbitraries.CurrencyLeg leg) {
        return Math.max(1L, leg.amountMinor() + leg.skewMinor());
    }

    private static List<NewPosting> postingsFor(List<LedgerArbitraries.CurrencyLeg> legs) {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        List<NewPosting> postings = new ArrayList<>();

        for (LedgerArbitraries.CurrencyLeg leg : legs) {
            postings.add(NewPosting.debit(accountA, leg.amountMinor(), leg.currency()));
            postings.add(NewPosting.credit(accountB, creditAmount(leg), leg.currency()));
            for (int i = 0; i < leg.extraPairs(); i++) {
                postings.add(NewPosting.debit(accountB, leg.amountMinor(), leg.currency()));
                postings.add(NewPosting.credit(accountA, leg.amountMinor(), leg.currency()));
            }
        }
        return postings;
    }

    private boolean verdict(List<NewPosting> postings) {
        try {
            service.requireBalanced(postings);
            return true;
        } catch (UnbalancedTransactionException e) {
            return false;
        }
    }
}
