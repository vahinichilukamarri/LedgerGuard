package com.ledgerguard.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * The only place BigDecimal is allowed to meet the ledger.
 *
 * <p>Inside the domain, money is always a {@code long} count of minor units
 * (cents, pence, yen) paired with an ISO-4217 currency code. BigDecimal exists
 * here purely to parse inbound API values and format outbound ones. It is never
 * stored, never summed, and never converted through {@code double}.
 */
public final class Money {

    private Money() {
    }

    /**
     * @throws IllegalArgumentException if the currency is unknown, or if the amount
     *                                  carries more precision than the currency has
     *                                  minor units (e.g. 10.255 USD). We refuse rather
     *                                  than round: silently dropping a fraction of a
     *                                  cent is how ledgers stop balancing.
     */
    public static long toMinorUnits(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        int fractionDigits = fractionDigits(currencyCode);
        try {
            return amount.movePointRight(fractionDigits)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "amount %s cannot be represented exactly in %s minor units (%d decimal places)"
                            .formatted(amount.toPlainString(), currencyCode, fractionDigits));
        }
    }

    /** Inverse of {@link #toMinorUnits}, for display at the API boundary only. */
    public static BigDecimal toMajorUnits(long minorUnits, String currencyCode) {
        return BigDecimal.valueOf(minorUnits, fractionDigits(currencyCode));
    }

    public static int fractionDigits(String currencyCode) {
        if (currencyCode == null || !currencyCode.matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO-4217 code, got: " + currencyCode);
        }
        Currency currency;
        try {
            currency = Currency.getInstance(normalize(currencyCode));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown ISO-4217 currency: " + currencyCode);
        }
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException("currency has no defined minor unit: " + currencyCode);
        }
        return digits;
    }

    public static String normalize(String currencyCode) {
        return currencyCode.toUpperCase(java.util.Locale.ROOT);
    }
}
