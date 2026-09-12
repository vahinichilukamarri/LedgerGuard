package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.AnomalyScore;
import com.ledgerguard.detection.CompositeAggregation;
import com.ledgerguard.detection.SignalScore;

import java.util.Comparator;
import java.util.List;

/**
 * Why the statistical composite is the number it is.
 *
 * <h2>Reusing the breakdown rather than rebuilding it</h2>
 *
 * Every signal already explained itself when it ran, in a sentence written by
 * the code that did the arithmetic — {@code AmountOutlierSignal} knows the
 * median, the scale basis and the sample size, and this class does not and
 * should not. Recomputing any of that here would mean two places that can
 * disagree about the same account, and the one a reviewer reads would be the
 * one furthest from the calculation.
 *
 * <p>So this is an arrangement of Phase 8's own output: the signals ordered by
 * how much composite they supplied, their weights made visible, and the
 * renormalisation that produced the composite shown rather than implied.
 *
 * <h2>The identity that makes it an explanation</h2>
 *
 * <pre>    sum(contribution) == 1</pre>
 *
 * whenever anything fired at all, exactly, to floating-point accumulation. A
 * breakdown whose parts do not add up to a whole is decoration; this one does,
 * and a test pins it. It is also why inapplicable signals appear with a
 * contribution of zero rather than being dropped: a reviewer needs to see that a
 * signal was silent because it could not judge, not to infer it from an absence.
 *
 * <p>Through Phases 10 to 12 the identity was {@code sum(contribution) ==
 * composite}, because the composite was then a weighted arithmetic mean and the
 * parts of a mean are in the same units as the mean. Phase 13 replaced it with a
 * power mean of degree three, whose parts sum to the composite cubed, so the
 * breakdown publishes shares instead. See {@link SignalContribution}.
 *
 * @param applicableWeight the total weight of the signals that could judge.
 *                         Once the composite's denominator; since Phase 13 the
 *                         composite has no denominator, and this is retained as
 *                         a plain statement of how much of the signal weight was
 *                         able to speak — which is worth knowing and no longer
 *                         arithmetic
 */
public record StatisticalExplanation(
        double composite,
        int applicableSignals,
        boolean wellEvidenced,
        double applicableWeight,
        List<SignalContribution> contributions) {

    public StatisticalExplanation {
        contributions = List.copyOf(contributions);
    }

    public static StatisticalExplanation of(AnomalyScore score) {
        double applicableWeight = score.signals().stream()
                .filter(SignalScore::applicable)
                .mapToDouble(signal -> signal.signal().weight())
                .sum();

        // The sum inside the cube root: what the shares are shares of.
        double totalPart = score.signals().stream()
                .mapToDouble(CompositeAggregation::part)
                .sum();

        // Ordered by contribution, not by the enum's declaration order: the
        // reading order a reviewer wants is "what put this score here first".
        // Ties fall back to the signal name so two runs agree exactly.
        List<SignalContribution> contributions = score.signals().stream()
                .map(signal -> SignalContribution.of(signal, totalPart))
                .sorted(Comparator.comparingDouble(SignalContribution::contribution).reversed()
                        .thenComparing(SignalContribution::signal))
                .toList();

        return new StatisticalExplanation(
                score.composite(),
                score.applicableSignals(),
                score.isWellEvidenced(),
                applicableWeight,
                contributions);
    }

    /** The signals that actually supplied score, worst first. */
    public List<SignalContribution> drivers() {
        return contributions.stream()
                .filter(contribution -> contribution.contribution() > 0)
                .toList();
    }

    /** Signals that could not judge at all. Their silence is not a clean bill of health. */
    public List<SignalContribution> unmeasurable() {
        return contributions.stream()
                .filter(contribution -> !contribution.applicable())
                .toList();
    }
}
