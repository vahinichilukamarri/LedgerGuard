package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.AnomalyScore;
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
 * <pre>    sum(contribution) == composite</pre>
 *
 * exactly, to floating-point accumulation. A breakdown whose parts do not add up
 * to the whole is decoration; this one does, and a test pins it. It is also why
 * inapplicable signals appear with a contribution of zero rather than being
 * dropped: a reviewer needs to see that a signal was silent because it could not
 * judge, not to infer it from an absence.
 *
 * @param applicableWeight the total weight of the signals that could judge. The
 *                         composite's denominator, published because it is the
 *                         whole of the renormalisation trade Phase 8 documents
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

        // Ordered by contribution, not by the enum's declaration order: the
        // reading order a reviewer wants is "what put this score here first".
        // Ties fall back to the signal name so two runs agree exactly.
        List<SignalContribution> contributions = score.signals().stream()
                .map(signal -> SignalContribution.of(signal, applicableWeight))
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

    /** The signals that actually supplied composite, worst first. */
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
