package com.ledgerguard.properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.accounts.AccountNotFoundException;
import com.ledgerguard.config.Money;
import com.ledgerguard.support.PropertyLedger;
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
 * The ledger invariant, asserted against what PostgreSQL actually holds.
 *
 * <p>{@code BalanceInvariantPropertyTest} proves the check is correct as a
 * function. This proves the rows it guards are correct as data — read back with
 * SQL, after the real migrations, through the real write path. The distinction
 * matters: a check that is right but bypassed, or right but committed inside a
 * transaction that partially rolled back, would pass the in-memory property and
 * fail here.
 *
 * <h2>Tries</h2>
 *
 * 120 per property rather than jqwik's 1000. Each try creates accounts and
 * performs one or more full write cycles against a real database; 1000 tries
 * would put a single property in the tens of minutes. The generators compensate
 * by forcing the boundary values (a single minor unit, zero-decimal and
 * three-decimal currencies) as edge cases rather than waiting for randomness to
 * produce them.
 */
@Tag("property")
class LedgerPersistencePropertyTest {

    /**
     * The headline property: after any generated payment, no
     * (transaction, currency) pair anywhere in the ledger has a non-zero net.
     *
     * <p>Note it checks the <em>whole</em> ledger, not just the transaction this
     * try wrote. That is deliberate. A bug that corrupted an unrelated
     * transaction would still be caught, and the assertion costs one aggregate
     * query either way.
     */
    @Property(tries = 120)
    void everyPersistedTransactionNetsToZeroPerCurrency(@ForAll("supported") String currency,
                                                        @ForAll("amounts") long amountMinor) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        JsonNode payment = PropertyLedger.pay(payer, payee, major(amountMinor, currency), currency);
        UUID transactionId = PropertyLedger.transactionIdOf(payment);

        assertThat(PropertyLedger.postingCountFor(transactionId))
                .as("a payment writes exactly two legs")
                .isEqualTo(2L);
        assertThat(PropertyLedger.transactionNetMinorUnits(transactionId)).isZero();
        assertThat(PropertyLedger.unbalancedTransactionCurrencyPairs())
                .as("no persisted transaction may be unbalanced in any currency")
                .isEmpty();
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isZero();
    }

    /**
     * The amount that arrives in the ledger is the amount that was asked for, in
     * that currency's minor units — 100 JPY is 100, not 10000, and 1.234 KWD is
     * 1234.
     */
    @Property(tries = 120)
    void thePersistedAmountIsTheRequestedAmountInMinorUnits(@ForAll("supported") String currency,
                                                            @ForAll("amounts") long amountMinor) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        PropertyLedger.pay(payer, payee, major(amountMinor, currency), currency);

        assertThat(PropertyLedger.balanceMinorUnits(payee, currency))
                .as("the payee is debited exactly the requested minor units")
                .isEqualTo(amountMinor);
        assertThat(PropertyLedger.balanceMinorUnits(payer, currency)).isEqualTo(-amountMinor);
    }

    /**
     * Currency isolation at the persistence layer.
     *
     * <p>Two independent payments in two different currencies. Each account's
     * derived balance reflects only its own currency, and neither payment is
     * visible in the other's figures. A system that summed amounts without
     * regard to the currency column would show one pair contaminated by the
     * other.
     */
    @Property(tries = 120)
    void postingsInDifferentCurrenciesNeverNetAgainstEachOther(
            @ForAll("twoDistinctCurrencies") java.util.List<String> currencies,
            @ForAll("amounts") long firstAmount,
            @ForAll("amounts") long secondAmount) {

        String first = currencies.get(0);
        String second = currencies.get(1);

        UUID firstPayer = PropertyLedger.createAccount(first);
        UUID firstPayee = PropertyLedger.createAccount(first);
        UUID secondPayer = PropertyLedger.createAccount(second);
        UUID secondPayee = PropertyLedger.createAccount(second);

        PropertyLedger.pay(firstPayer, firstPayee, major(firstAmount, first), first);
        PropertyLedger.pay(secondPayer, secondPayee, major(secondAmount, second), second);

        assertThat(PropertyLedger.balanceMinorUnits(firstPayee, first)).isEqualTo(firstAmount);
        assertThat(PropertyLedger.balanceMinorUnits(secondPayee, second)).isEqualTo(secondAmount);

        // The second currency's postings contribute nothing to the first pair.
        assertThat(PropertyLedger.balanceMinorUnits(firstPayee, second)).isZero();
        assertThat(PropertyLedger.balanceMinorUnits(firstPayer, second)).isZero();
        assertThat(PropertyLedger.balanceMinorUnits(secondPayee, first)).isZero();
        assertThat(PropertyLedger.balanceMinorUnits(secondPayer, first)).isZero();

        // And an account only ever holds postings in its own currency, so the
        // currency-blind total equals the currency-scoped one.
        assertThat(PropertyLedger.balanceMinorUnitsAllCurrencies(firstPayee)).isEqualTo(firstAmount);
        assertThat(PropertyLedger.balanceMinorUnitsAllCurrencies(secondPayee)).isEqualTo(secondAmount);
    }

    /**
     * A payment in a currency the accounts are not denominated in is refused,
     * which is what keeps the previous property true in the first place: there
     * is no way to get a EUR posting onto a USD account.
     */
    @Property(tries = 120)
    void aPaymentInTheWrongCurrencyIsRefusedAndWritesNothing(
            @ForAll("twoDistinctCurrencies") java.util.List<String> currencies,
            @ForAll("amounts") long amountMinor) {

        String accountCurrency = currencies.get(0);
        String wrongCurrency = currencies.get(1);

        UUID payer = PropertyLedger.createAccount(accountCurrency);
        UUID payee = PropertyLedger.createAccount(accountCurrency);

        long postingsBefore = PropertyLedger.postingCount();
        long paymentsBefore = PropertyLedger.paymentCount();

        assertThatThrownBy(() ->
                PropertyLedger.pay(payer, payee, major(amountMinor, wrongCurrency), wrongCurrency))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(PropertyLedger.postingCount()).isEqualTo(postingsBefore);
        assertThat(PropertyLedger.paymentCount()).isEqualTo(paymentsBefore);
        assertThat(PropertyLedger.balanceMinorUnitsAllCurrencies(payer)).isZero();
        assertThat(PropertyLedger.balanceMinorUnitsAllCurrencies(payee)).isZero();
    }

    /**
     * Every way of being refused leaves the ledger exactly as it was: no
     * transaction row, no posting, no payment, no drift in any balance.
     */
    @Property(tries = 120)
    void aRefusedPaymentWritesNothingAtAll(@ForAll("supported") String currency,
                                           @ForAll("amounts") long amountMinor,
                                           @ForAll("rejections") Rejection rejection) {
        UUID payer = PropertyLedger.createAccount(currency);
        UUID payee = PropertyLedger.createAccount(currency);

        long postingsBefore = PropertyLedger.postingCount();
        long transactionsBefore = PropertyLedger.transactionCount();
        long paymentsBefore = PropertyLedger.paymentCount();
        long ledgerNetBefore = PropertyLedger.ledgerNetMinorUnits();

        assertThatThrownBy(() -> rejection.attempt(payer, payee, amountMinor, currency))
                .as("%s must be refused", rejection)
                .isInstanceOf(rejection.expected());

        assertThat(PropertyLedger.postingCount()).isEqualTo(postingsBefore);
        assertThat(PropertyLedger.transactionCount()).isEqualTo(transactionsBefore);
        assertThat(PropertyLedger.paymentCount()).isEqualTo(paymentsBefore);
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isEqualTo(ledgerNetBefore);
        assertThat(PropertyLedger.balanceMinorUnitsAllCurrencies(payer)).isZero();
        assertThat(PropertyLedger.balanceMinorUnitsAllCurrencies(payee)).isZero();
    }

    /** The ways a payment can be legitimately refused, as executable cases. */
    enum Rejection {
        /** More decimal places than the currency has minor units. */
        SUB_MINOR_PRECISION(IllegalArgumentException.class) {
            @Override
            void attempt(UUID payer, UUID payee, long amountMinor, String currency) {
                // One extra digit, and that digit is a 1 rather than whatever
                // falls out of arithmetic. Appending a digit that can carry —
                // 0.009 + 0.001 is 0.01, which is perfectly representable —
                // would quietly produce a legal amount for every generated
                // value ending in 9.
                BigDecimal tooFine = BigDecimal.valueOf(
                        amountMinor * 10 + 1, Money.fractionDigits(currency) + 1);
                PropertyLedger.pay(payer, payee, tooFine, currency);
            }
        },
        /** A destination account that does not exist. */
        UNKNOWN_DESTINATION(AccountNotFoundException.class) {
            @Override
            void attempt(UUID payer, UUID payee, long amountMinor, String currency) {
                PropertyLedger.pay(payer, UUID.randomUUID(),
                        Money.toMajorUnits(amountMinor, currency), currency);
            }
        },
        /** A currency code that is not usable at all. */
        UNUSABLE_CURRENCY(IllegalArgumentException.class) {
            @Override
            void attempt(UUID payer, UUID payee, long amountMinor, String currency) {
                PropertyLedger.pay(payer, payee, BigDecimal.ONE, "ZZZ");
            }
        };

        private final Class<? extends RuntimeException> expected;

        Rejection(Class<? extends RuntimeException> expected) {
            this.expected = expected;
        }

        Class<? extends RuntimeException> expected() {
            return expected;
        }

        abstract void attempt(UUID payer, UUID payee, long amountMinor, String currency);
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
    Arbitrary<java.util.List<String>> twoDistinctCurrencies() {
        return LedgerArbitraries.supportedCurrencies().list().ofSize(2).uniqueElements();
    }

    @Provide
    Arbitrary<Rejection> rejections() {
        return net.jqwik.api.Arbitraries.of(Rejection.class);
    }

    // ---------------------------------------------------------------- helpers

    static BigDecimal major(long minorUnits, String currency) {
        return Money.toMajorUnits(minorUnits, currency);
    }

}
