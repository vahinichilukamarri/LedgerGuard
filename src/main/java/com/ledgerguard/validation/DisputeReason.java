package com.ledgerguard.validation;

/**
 * Why a payment was disputed.
 *
 * <h2>Only one of these is a fraud label</h2>
 *
 * Treating every chargeback as evidence of fraud is the most common way a fraud
 * label set gets quietly poisoned, and it poisons it in the worst direction: the
 * contaminating cases are disproportionately <em>ordinary</em> accounts having
 * an ordinary commercial argument, so the detector is measured against a target
 * that includes behaviour it was never meant to catch and never could.
 *
 * <p>A cardholder who never received their goods has a dispute with a merchant.
 * A cardholder billed twice has a dispute with a processor. Neither says
 * anything about whether the payment was fraudulent, so neither becomes a
 * label. They are recorded because they are real events and because their rate
 * is worth knowing; they are deliberately unused.
 */
public enum DisputeReason {

    /** The cardholder says they did not authorise this. The only label-bearing reason. */
    FRAUDULENT,

    /** Goods or services never arrived. A merchant dispute. */
    NOT_RECEIVED,

    /** Billed more than once. A processing dispute. */
    DUPLICATE,

    /** A technical authorisation problem. */
    AUTHORISATION,

    /** Anything else the scheme did not classify. */
    OTHER;

    /** Whether this dispute is evidence that the payment itself was illegitimate. */
    public boolean isFraudEvidence() {
        return this == FRAUDULENT;
    }
}
