package com.ledgerguard.validation;

/**
 * What somebody concluded about an account.
 *
 * <h2>Three values, not two</h2>
 *
 * {@link #UNCLEAR} is not a reviewer failing to do their job. An account a
 * careful person cannot judge from the evidence available is a fact about how
 * hard this problem is, and forcing a binary would manufacture agreement that
 * does not exist — inflating both the apparent quality of the labels and every
 * metric computed from them.
 *
 * <p>The same instinct as Phase 8's three signal states: the difference between
 * "looked and found nothing" and "could not look" is information, and collapsing
 * it is how a system starts lying quietly.
 */
public enum Verdict {

    /** The behaviour was judged genuinely unusual or illegitimate. */
    ANOMALOUS,

    /** The behaviour was judged ordinary. */
    BENIGN,

    /** Looked at, and not judgeable from what was available. Excluded from metrics. */
    UNCLEAR;

    /** Whether this verdict can appear in a confusion matrix at all. */
    public boolean isDecisive() {
        return this != UNCLEAR;
    }
}
