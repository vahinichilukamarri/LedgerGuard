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
 * <h2>Renormalisation, and the trade it makes</h2>
 *
 * The composite divides by the weight of the signals that were <em>applicable</em>,
 * not by the total weight of all five. Without that, an account with only two
 * measurable signals could never exceed 0.45 however extreme its behaviour, and
 * thin-history accounts — where a good deal of fraud lives — would be
 * structurally invisible.
 *
 * <p>The cost is the mirror image: with one applicable signal firing hard, the
 * composite reads 1.0 on a single piece of evidence. That is why
 * {@link AnomalyScore#applicableSignals()} travels with the score rather than
 * being folded into it, and why {@link AnomalyScore#isWellEvidenced()} exists.
 * The alternative — quietly damping thin scores by some confidence factor —
 * would bury the same weakness inside a number that looked more trustworthy.
 * Better to report both and let the reader see the shape of the evidence.
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
        double weighted = 0;
        double applicableWeight = 0;
        int applicable = 0;

        for (AnomalySignal signal : signals) {
            SignalScore score = signal.evaluate(activity, settings);
            scores.add(score);

            if (!score.applicable()) {
                continue;
            }
            applicable++;
            double weight = score.signal().weight();
            weighted += weight * score.score();
            applicableWeight += weight;
        }

        // No applicable signal is not a clean bill of health; it is an absence
        // of evidence, and it scores zero with applicableSignals = 0 to say so.
        double composite = applicableWeight == 0 ? 0.0 : weighted / applicableWeight;

        return new AnomalyScore(activity.accountId(), activity.asOf(), composite, applicable, scores);
    }

    public DetectionSettings settings() {
        return settings;
    }
}
