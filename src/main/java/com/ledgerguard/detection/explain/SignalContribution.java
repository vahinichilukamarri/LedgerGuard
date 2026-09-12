package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.CompositeAggregation;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;

import java.util.UUID;

/**
 * One signal's share of the composite, alongside everything Phase 8 already
 * said about it.
 *
 * <h2>What this adds to the Phase 8 breakdown</h2>
 *
 * One number. {@link SignalScore} already carries the statistic, the normalised
 * score, the applicability and a sentence of prose; that is not rebuilt here, it
 * is wrapped. What it cannot carry is how much of <em>this account's</em> score
 * the signal supplied, because that depends on what the other signals did.
 *
 * <h2>A share, since Phase 13</h2>
 *
 * Through Phases 10 to 12 this was {@code effectiveWeight x score}, in composite
 * points, and the five of them summed to the composite exactly. That identity
 * was a property of the weighted arithmetic mean, and Phase 13 replaced that
 * mean with a power mean of degree three, so it no longer holds: the parts now
 * sum to the composite <em>cubed</em>.
 *
 * <p>Rather than publish a quantity in a cubed space nobody has intuitions
 * about, {@link #contribution} is that part as a <b>share of the total</b>. The
 * new identity is that the shares sum to one, which is exact, and which reads
 * the way a reviewer wants to read it: this signal supplied seventy per cent of
 * the score. It also matches the language Phase 10's ML attribution has used for
 * its own drivers since it was written, so the two halves of an explanation
 * finally speak the same way.
 *
 * <p>{@code effectiveWeight} is gone with the mean that defined it. There is no
 * renormalisation any more, so a weight-as-a-fraction-of-the-applicable-weight
 * is a number that no longer participates in anything, and keeping it would have
 * left a field that looks like it drives the score and does not.
 *
 * @param weight       the fixed, unfitted weight from {@link Signal}
 * @param contribution this signal's share of the score, in {@code [0,1]}. Zero
 *                     for a signal that did not fire; across all five they sum
 *                     to one whenever anything fired at all
 */
public record SignalContribution(
        String signal,
        boolean applicable,
        boolean fired,
        Double statistic,
        double score,
        double weight,
        double contribution,
        String explanation,
        UUID subjectId) {

    /**
     * @param totalPart the sum of {@link CompositeAggregation#part} across every
     *                  signal, which is the composite cubed. Zero when nothing
     *                  fired, in which case every share is zero rather than
     *                  undefined
     */
    static SignalContribution of(SignalScore score, double totalPart) {
        double share = totalPart > 0 ? CompositeAggregation.part(score) / totalPart : 0.0;

        return new SignalContribution(
                score.signal().wireName(),
                score.applicable(),
                score.fired(),
                Double.isNaN(score.statistic()) ? null : score.statistic(),
                score.score(),
                score.signal().weight(),
                share,
                score.explanation(),
                score.subjectId());
    }
}
