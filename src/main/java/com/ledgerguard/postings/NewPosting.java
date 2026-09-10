package com.ledgerguard.postings;

import com.ledgerguard.config.Money;

import java.util.UUID;

/**
 * An intended posting, before it has been checked for balance or persisted.
 *
 * <p>This is what callers hand to the transaction service. Nothing turns a
 * {@code NewPosting} into a {@link Posting} except a balance check that passes.
 */
public record NewPosting(UUID accountId, PostingType type, long amountMinor, String currency) {

    public NewPosting {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be strictly positive, got: " + amountMinor);
        }
        Money.fractionDigits(currency); // rejects null, malformed and unknown ISO-4217 codes
        currency = Money.normalize(currency);
    }

    public static NewPosting debit(UUID accountId, long amountMinor, String currency) {
        return new NewPosting(accountId, PostingType.DEBIT, amountMinor, currency);
    }

    public static NewPosting credit(UUID accountId, long amountMinor, String currency) {
        return new NewPosting(accountId, PostingType.CREDIT, amountMinor, currency);
    }

    /** +amount for DEBIT, -amount for CREDIT. */
    public long signedAmountMinor() {
        return type.sign() * amountMinor;
    }
}
