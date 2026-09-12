package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;

import java.util.UUID;

/**
 * One signal's share of the composite, alongside everything Phase 8 already
 * said about it.
 *
 * <h2>What this adds to the Phase 8 breakdown</h2>
 *
 * Two numbers, and only two. {@link SignalScore} already carries the statistic,
 * the normalised score, the applicability and a sentence of prose; that is not
 * rebuilt here, it is wrapped. What it cannot carry is how much of <em>this
 * account's</em> composite the signal supplied, because that depends on which
 * other signals were applicable — the same signal at the same score contributes
 * 0.25 of a five-signal composite and 0.56 of a two-signal one.
 *
 * <p>So {@link #effectiveWeight} is the signal's weight renormalised over the
 * applicable set, and {@link #contribution} is {@code effectiveWeight x score}:
 * the points of composite this signal actually put on the board. They sum,
 * across all five, to the composite exactly, which is the property that makes
 * the breakdown an explanation rather than a list of numbers that happen to
 * appear nearby.
 *
 * @param weight          the fixed, unfitted weight from {@link Signal}
 * @param effectiveWeight that weight as a share of the applicable weight; zero
 *                        for a signal that could not judge
 * @param contribution    {@code effectiveWeight x score}, in composite points
 */
public record SignalContribution(
        String signal,
        boolean applicable,
        boolean fired,
        Double statistic,
        double score,
        double weight,
        double effectiveWeight,
        double contribution,
        String explanation,
        UUID subjectId) {

    static SignalContribution of(SignalScore score, double applicableWeight) {
        double effectiveWeight = score.applicable() && applicableWeight > 0
                ? score.signal().weight() / applicableWeight
                : 0.0;

        return new SignalContribution(
                score.signal().wireName(),
                score.applicable(),
                score.fired(),
                Double.isNaN(score.statistic()) ? null : score.statistic(),
                score.score(),
                score.signal().weight(),
                effectiveWeight,
                effectiveWeight * score.score(),
                score.explanation(),
                score.subjectId());
    }
}
