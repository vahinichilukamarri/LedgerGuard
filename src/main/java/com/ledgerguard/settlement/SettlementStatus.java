package com.ledgerguard.settlement;

/**
 * The state a processor reports for a movement of money.
 *
 * <p>Deliberately not the same vocabulary as {@code PaymentStatus}. The
 * external system is a different system with its own lifecycle, and pretending
 * the two enums are interchangeable is how reconciliation quietly stops
 * comparing anything.
 */
public enum SettlementStatus {

    /** The processor considers the money moved. This is what a POSTED payment should map to. */
    SETTLED,

    /** Accepted but not yet final. Internally POSTED against this is a real disagreement. */
    PENDING,

    /** The processor rejected it. Internally POSTED against this is a serious disagreement. */
    FAILED
}
