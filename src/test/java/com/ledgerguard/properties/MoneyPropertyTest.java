package com.ledgerguard.properties;

import com.ledgerguard.config.Money;
import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.properties.LedgerArbitraries.SubMinorAmount;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Properties of the money boundary: the one place a decimal becomes ledger money.
 *
 * <p>All in memory and all at jqwik's default 1000 tries — {@code Money} touches
 * nothing but {@code BigDecimal} and {@code java.util.Currency}, so tries are
 * free here and there is no reason to run fewer.
 *
 * <p>These are the properties that most directly cover the phase brief's
 * currency and amount edge cases: zero-decimal currencies, three-decimal
 * currencies, unusable currency codes, a single minor unit, sub-minor precision,
 * and the actual top of the {@code long} range.
 */
@Tag("property")
class MoneyPropertyTest {

    /**
     * The round trip that everything else rests on. If this ever loses a minor
     * unit, every balance in the system is wrong by that much and no other test
     * would necessarily notice.
     */
    @Property(tries = 1000)
    void minorUnitsRoundTripExactlyThroughDecimal(@ForAll("supported") String currency,
                                                  @ForAll("chainAmounts") long minorUnits) {
        BigDecimal asDecimal = Money.toMajorUnits(minorUnits, currency);

        assertThat(Money.toMinorUnits(asDecimal, currency))
                .as("%s %s must survive the trip to decimal and back", minorUnits, currency)
                .isEqualTo(minorUnits);
    }

    /** Same round trip, run against the extremes of the representation on purpose. */
    @Property(tries = 1000)
    void roundTripHoldsAtTheBoundariesOfTheRepresentation(@ForAll("supported") String currency,
                                                          @ForAll("boundaryAmounts") long minorUnits) {
        BigDecimal asDecimal = Money.toMajorUnits(minorUnits, currency);

        assertThat(Money.toMinorUnits(asDecimal, currency)).isEqualTo(minorUnits);
    }

    /**
     * The decimal scale is the currency's, not the caller's. A JPY amount comes
     * back with no decimal places at all; a KWD amount with three.
     */
    @Property(tries = 1000)
    void decimalScaleAlwaysMatchesTheCurrency(@ForAll("supported") String currency,
                                              @ForAll("chainAmounts") long minorUnits) {
        assertThat(Money.toMajorUnits(minorUnits, currency).scale())
                .isEqualTo(Money.fractionDigits(currency));
    }

    /**
     * Precision finer than the currency can express is refused, never rounded.
     *
     * <p>Stated the strong way: not only does the conversion throw, but the
     * amount really was finer than the currency — so this cannot pass by
     * accidentally generating representable values.
     */
    @Property(tries = 1000)
    void subMinorPrecisionIsAlwaysRejected(@ForAll("subMinor") SubMinorAmount generated) {
        assertThat(generated.amount().stripTrailingZeros().scale())
                .as("the generated amount must genuinely exceed %s's %d decimal places",
                        generated.currency(), generated.currencyFractionDigits())
                .isGreaterThan(generated.currencyFractionDigits());

        assertThatThrownBy(() -> Money.toMinorUnits(generated.amount(), generated.currency()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be represented exactly");
    }

    /**
     * An unusable currency code is refused at every entry point, and refused the
     * same way — never normalised into something valid, never defaulted.
     */
    @Property(tries = 1000)
    void unusableCurrencyCodesAreRejectedEverywhere(@ForAll("unusable") String currency,
                                                    @ForAll("chainAmounts") long minorUnits) {
        assertThatThrownBy(() -> Money.fractionDigits(currency))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Money.toMinorUnits(BigDecimal.ONE, currency))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Money.toMajorUnits(minorUnits, currency))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> NewPosting.debit(UUID.randomUUID(), minorUnits, currency))
                .as("a posting must not be constructible in an unusable currency")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Letter case is not information. "usd", "Usd" and "USD" are the same currency. */
    @Property(tries = 1000)
    void currencyCaseIsNormalisedNotHonoured(@ForAll("mixedCase") String currency,
                                             @ForAll("chainAmounts") long minorUnits) {
        String upper = currency.toUpperCase(java.util.Locale.ROOT);

        assertThat(Money.fractionDigits(currency)).isEqualTo(Money.fractionDigits(upper));
        assertThat(Money.toMajorUnits(minorUnits, currency))
                .isEqualByComparingTo(Money.toMajorUnits(minorUnits, upper));

        NewPosting posting = NewPosting.debit(UUID.randomUUID(), minorUnits, currency);
        assertThat(posting.currency())
                .as("the stored currency is always the canonical upper-case code")
                .isEqualTo(upper);
    }

    /**
     * Direction lives in the posting type, never in the sign of the amount, so a
     * zero or negative amount is not a posting at all.
     */
    @Property(tries = 1000)
    void nonPositiveAmountsAreNeverPostings(@ForAll("supported") String currency,
                                            @ForAll("chainAmounts") long minorUnits) {
        assertThatThrownBy(() -> NewPosting.debit(UUID.randomUUID(), 0L, currency))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> NewPosting.credit(UUID.randomUUID(), -minorUnits, currency))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A decimal too large for a {@code long} of minor units is rejected rather
     * than silently truncated to whatever the low 64 bits happen to be.
     *
     * <p>This is the overflow boundary the chain generators deliberately stay
     * below, probed here where a failure means one specific thing.
     */
    @Property(tries = 1000)
    void amountsBeyondTheRepresentationAreRejectedNotTruncated(
            @ForAll("supported") String currency,
            @ForAll("boundaryAmounts") long minorUnits) {
        BigDecimal justPastTheTop = Money.toMajorUnits(Long.MAX_VALUE, currency)
                .add(Money.toMajorUnits(minorUnits, currency));

        assertThatThrownBy(() -> Money.toMinorUnits(justPastTheTop, currency))
                .as("%s in %s exceeds a long of minor units and must be refused",
                        justPastTheTop.toPlainString(), currency)
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------- generators

    @Provide
    Arbitrary<String> supported() {
        return LedgerArbitraries.supportedCurrencies();
    }

    @Provide
    Arbitrary<String> mixedCase() {
        return LedgerArbitraries.mixedCaseCurrencies();
    }

    @Provide
    Arbitrary<String> unusable() {
        return LedgerArbitraries.unusableCurrencies();
    }

    @Provide
    Arbitrary<Long> chainAmounts() {
        return LedgerArbitraries.chainAmountsMinor();
    }

    @Provide
    Arbitrary<Long> boundaryAmounts() {
        return LedgerArbitraries.boundaryAmountsMinor();
    }

    @Provide
    Arbitrary<SubMinorAmount> subMinor() {
        return LedgerArbitraries.subMinorAmounts();
    }
}
