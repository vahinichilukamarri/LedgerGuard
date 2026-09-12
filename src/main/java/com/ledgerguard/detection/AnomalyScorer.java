package com.ledgerguard.detection;

import java.util.ArrayList;
import java.util.List;

/**
 * Combines the signals into one score.
 *
 * <h2>A weighted sum, deliberately</h2>
 *
 * Nothing here is clever, and that is the design. There is no labelled data in
 * this phase, so any more elaborate combiner — a learned ensemble, a
 * probabilistic mixture — would have its parameters chosen by taste and then be
 * much harder to argue with than a weighted sum whose weights are visible
 * constants. Learning the combination is precisely what the ML layer is for, and
 * building half of it here without data would be the expensive kind of
 * premature.
 *
 * <h2>Combination, and where it lives</h2>
 *
 * The arithmetic is {@link CompositeAggregation}, which Phase 13 replaced after
 * Phase 12 measured what the original cost. Phases 8 to 12 used a weighted
 * arithmetic mean renormalised over the applicable signals; that function could
 * not flag an account on one signal once three of the five applied, however
 * extreme that signal was, and it made thin-history accounts easier to flag than
 * fully-measured ones. It is now a weighted power mean of degree three,
 * unrenormalised. See that class for the derivation and for what changed about
 * the meaning of the number.
 *
 * <p>What did not change: {@link AnomalyScore#applicableSignals()} still travels
 * beside the score rather than being folded into it, and
 * {@link AnomalyScore#isWellEvidenced()} still exists. They matter more now, not
 * less — the composite no longer encodes how much of the evidence was
 * measurable, so those two fields are the only place that information lives.
 */
public class AnomalyScorer {

    private final List<AnomalySignal> signals;
    private final DetectionSettings settings;

    public AnomalyScorer(List<AnomalySignal> signals, DetectionSettings settings) {
        this.signals = List.copyOf(signals);
        this.settings = settings;
    }

    /** Every signal, in a fixed order, so two runs over the same data agree exactly. */
    public static AnomalyScorer withAllSignals(DetectionSettings settings) {
        return new AnomalyScorer(List.of(
                new com.ledgerguard.detection.signals.AmountOutlierSignal(),
                new com.ledgerguard.detection.signals.VelocitySignal(),
                new com.ledgerguard.detection.signals.BurstSignal(),
                new com.ledgerguard.detection.signals.ReconciliationMismatchSignal(),
                new com.ledgerguard.detection.signals.RefundReversalRateSignal()),
                settings);
    }

    public AnomalyScore score(AccountActivity activity) {
        List<SignalScore> scores = new ArrayList<>(signals.size());
        int applicable = 0;

        for (AnomalySignal signal : signals) {
            SignalScore score = signal.evaluate(activity, settings);
            scores.add(score);
            if (score.applicable()) {
                applicable++;
            }
        }

        // No applicable signal is not a clean bill of health; it is an absence
        // of evidence, and it scores zero with applicableSignals = 0 to say so.
        double composite = CompositeAggregation.combine(scores);

        return new AnomalyScore(activity.accountId(), activity.asOf(), composite, applicable, scores);
    }

    public DetectionSettings settings() {
        return settings;
    }
}
