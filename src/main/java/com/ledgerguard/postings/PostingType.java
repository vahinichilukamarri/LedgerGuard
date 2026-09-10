package com.ledgerguard.postings;

/**
 * Sign convention for this ledger: a DEBIT increases an account balance,
 * a CREDIT decreases it. So {@code balance = sum(DEBIT) - sum(CREDIT)}.
 *
 * <p>A payment therefore CREDITs the source account (money leaves) and DEBITs
 * the destination account (money arrives).
 */
public enum PostingType {
    DEBIT,
    CREDIT;

    /** +1 for DEBIT, -1 for CREDIT. Used when netting a transaction to zero. */
    public int sign() {
        return this == DEBIT ? 1 : -1;
    }

    public PostingType opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
