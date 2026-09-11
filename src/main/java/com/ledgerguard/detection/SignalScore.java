package com.ledgerguard.detection;

import java.util.UUID;

/**
 * What one signal concluded, and why.
 *
 * <h2>Three states, not two</h2>
 *
 * A signal can fire, can decline to fire, or can have <b>nothing to say</b> —
 * and the third is not the same as the second. An account with four payments has
 * not been found innocent by the amount signal; the signal has no baseline to
 * judge it against. Collapsing those two into "score 0" is how a detection layer
 * quietly reports that brand-new accounts are the safest ones on the system.
 *
 * <p>So {@link #applicable()} is carried separately, and the composite only
 * averages over signals that had enough data. {@link #explanation()} is required
 * in every state, including the inapplicable one, because an operator reading a
 * score needs to know whether a quiet signal looked and found nothing or never
 * looked at all.
 *
 * @param statistic   the raw statistic on its own scale — a modified z, or a
 *                    surprisal. Kept alongside the normalised score because the
 *                    normalisation is lossy above saturation and someone
 *                    investigating wants the real number
 * @param score       the normalised contribution in {@code [0,1]}
 * @param subjectId   the payment or transaction that caused the signal to fire,
 *                    when the signal can point at one
 */
public record SignalScore(
        Signal signal,
        boolean applicable,
        double statistic,
        double score,
        String explanation,
        UUID subjectId) {

    public SignalScore {
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("every signal outcome must explain itself");
        }
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("normalised score must be within [0,1], got: " + score);
        }
    }

    /** The signal looked, and had enough history to judge. */
    public static SignalScore of(Signal signal, double statistic, String explanation, UUID subjectId) {
        return new SignalScore(signal, true, statistic, signal.normalise(statistic), explanation, subjectId);
    }

    public static SignalScore of(Signal signal, double statistic, String explanation) {
        return of(signal, statistic, explanation, null);
    }

    /**
     * The signal could not judge: too little history, or none of the right kind.
     *
     * <p>Scores zero and is excluded from the composite, rather than counted as
     * a vote of confidence.
     */
    public static SignalScore insufficientData(Signal signal, String why) {
        return new SignalScore(signal, false, Double.NaN, 0.0, "insufficient data: " + why, null);
    }

    /** Fired, as opposed to having looked and found nothing. */
    public boolean fired() {
        return applicable && score > 0.0;
    }
}
