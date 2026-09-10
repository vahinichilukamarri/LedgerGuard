package com.ledgerguard.reconciliation;

/**
 * The complete set of outcomes when one internal transaction is compared
 * against the external world. Every comparison lands in exactly one.
 */
public enum DiscrepancyType {

    /** The two sides agree on amount, currency and state. Produces no incident. */
    MATCHED(Severity.LOW),

    /**
     * We recorded the money moving; the processor has no record of it.
     * Example: we posted a $250 payment, the settlement file has nothing.
     */
    MISSING_SETTLEMENT(Severity.MEDIUM),

    /**
     * Both sides know about it and disagree on how much.
     * Example: we recorded $250, the processor settled $200.
     */
    AMOUNT_MISMATCH(Severity.MEDIUM),

    /**
     * The processor has more than one record for one internal transaction.
     * HIGH regardless of size: somebody was probably paid or charged twice, and
     * that means a control failed rather than a number being off.
     */
    DUPLICATE_SETTLEMENT(Severity.HIGH),

    /**
     * Amounts agree, states do not. Example: we consider it POSTED, the
     * processor still says PENDING, or says FAILED.
     *
     * <p>LOW, but still an incident. Agreeing amounts are exactly what makes
     * this easy to overlook, and "we think it settled, they think it failed" is
     * usually the precursor to a real loss rather than a harmless annotation.
     */
    STATUS_MISMATCH(Severity.LOW),

    /**
     * The processor moved money we have no transaction for.
     * HIGH regardless of size: this is money moving without the ledger
     * authorising it, which is the most alarming shape a discrepancy can take.
     */
    UNEXPECTED_EXTERNAL_TRANSACTION(Severity.HIGH);

    private final Severity baseSeverity;

    DiscrepancyType(Severity baseSeverity) {
        this.baseSeverity = baseSeverity;
    }

    /** The floor for this type, before any amount-based escalation. */
    public Severity baseSeverity() {
        return baseSeverity;
    }

    public boolean isDiscrepancy() {
        return this != MATCHED;
    }
}
