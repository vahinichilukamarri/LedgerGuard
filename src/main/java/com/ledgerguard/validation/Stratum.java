package com.ledgerguard.validation;

/**
 * Which sampling pool a reviewed account was drawn from.
 *
 * <h2>This is the recall denominator</h2>
 *
 * Left to themselves, reviewers label what the detector shows them. That
 * produces a label set in which every anomalous account the detector missed is
 * invisible — so false negatives cannot be counted, and recall is not merely
 * imprecise but <b>structurally unmeasurable</b>. The measured number would
 * instead be "of the accounts we flagged, how many did reviewers agree with",
 * which is precision wearing recall's name.
 *
 * <p>{@link #AUDIT} is the fix: a random sample of accounts the detector did
 * <em>not</em> flag, labelled anyway, at some cost in reviewer time spent on
 * accounts that are almost all ordinary. It cannot be retrofitted — a label set
 * gathered without it can never have the missing stratum added after the fact,
 * because the accounts that would have been sampled are no longer a random
 * sample of anything.
 *
 * <p>Because the two strata are sampled at very different rates, counts from
 * them cannot simply be added. Each labelled account stands for a known number
 * of unlabelled ones, and the evaluator reweights accordingly.
 */
public enum Stratum {

    /** Drawn from accounts the detector surfaced. Dense in positives, and biased. */
    FLAGGED,

    /** Drawn at random from accounts it did not. Sparse, expensive, and the only honest denominator. */
    AUDIT
}
