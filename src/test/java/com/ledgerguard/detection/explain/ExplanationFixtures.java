package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.AnomalyScore;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;
import com.ledgerguard.detection.ml.FeatureAttribution;
import com.ledgerguard.detection.ml.FeatureVector;
import com.ledgerguard.detection.ml.MlExplanation;
import com.ledgerguard.detection.ml.ModelMetadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hand-built explanations, for the tests that are about the explaining rather
 * than about the detecting.
 *
 * <p>Assembled directly rather than by scoring a ledger, because a test of the
 * reconciliation narrative needs to place a specific account in a specific
 * agreement state, and getting there through the signals and a trained forest
 * would mean tuning a synthetic ledger until the two scores landed either side
 * of two thresholds. That test would be about the tuning. The pipeline is
 * exercised end to end by {@code ExplanationFlowIntegrationTest} instead.
 */
public final class ExplanationFixtures {

    public static final Instant AS_OF = Instant.parse("2026-09-12T10:00:00Z");
    public static final String NO_MODEL_REASON = ExplanationService.NO_MODEL;
    public static final UUID ACCOUNT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private ExplanationFixtures() {
    }

    /**
     * An {@link AnomalyScore} with the given raw statistics, composited by the
     * same renormalisation {@code AnomalyScorer} uses.
     *
     * @param statistics signals to treat as applicable, with their raw
     *                   statistic. Any signal left out is reported as having
     *                   had too little data to judge
     */
    public static AnomalyScore score(Map<Signal, Double> statistics) {
        List<SignalScore> scores = new ArrayList<>();
        double weighted = 0;
        double applicableWeight = 0;

        for (Signal signal : Signal.values()) {
            Double statistic = statistics.get(signal);
            if (statistic == null) {
                scores.add(SignalScore.insufficientData(signal, "not part of this fixture"));
                continue;
            }
            SignalScore score = SignalScore.of(signal, statistic, "fixture statistic " + statistic);
            scores.add(score);
            weighted += signal.weight() * score.score();
            applicableWeight += signal.weight();
        }

        double composite = applicableWeight == 0 ? 0 : weighted / applicableWeight;
        return new AnomalyScore(ACCOUNT, AS_OF, composite, statistics.size(), scores);
    }

    /** A score whose composite lands where the caller needs it, on one signal. */
    public static AnomalyScore statisticalScore(double composite) {
        // AMOUNT_OUTLIER alone is applicable, so the composite is that signal's
        // normalised score exactly and a target composite is one inversion away.
        double statistic = Signal.AMOUNT_OUTLIER.threshold()
                + composite * (Signal.AMOUNT_OUTLIER.saturation() - Signal.AMOUNT_OUTLIER.threshold());
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.AMOUNT_OUTLIER, statistic);
        return score(statistics);
    }

    /** A quiet score: every signal applicable, none of them firing. */
    public static AnomalyScore quietScore() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        for (Signal signal : Signal.values()) {
            statistics.put(signal, 0.0);
        }
        return score(statistics);
    }

    /**
     * An {@link MlExplanation} at {@code score}, isolating on the named
     * features in the order given.
     *
     * <p>Excess bits descend from the first named feature, so the driver order
     * is the argument order; everything unnamed sits slightly negative, which is
     * what a feature that kept putting the account on the crowded side looks
     * like.
     */
    public static MlExplanation mlExplanation(double score, String... isolatingFeatures) {
        List<String> isolating = List.of(isolatingFeatures);
        List<FeatureAttribution> attributions = new ArrayList<>();

        double totalExcess = 0;
        for (int rank = 0; rank < isolating.size(); rank++) {
            totalExcess += 1.0 / (rank + 1);
        }

        for (int index = 0; index < FeatureVector.NAMES.length; index++) {
            String name = FeatureVector.NAMES[index];
            int rank = isolating.indexOf(name);

            double excess = rank < 0 ? -0.2 : 1.0 / (rank + 1);
            double share = rank < 0 ? 0.0 : excess / totalExcess;
            double percentile = rank < 0 ? 0.5 : 0.99;
            double value = rank < 0 ? 0.0 : 9.0;

            attributions.add(new FeatureAttribution(
                    name, index, value, 2.0, excess + 2.0, excess, share, percentile, 0.0));
        }

        attributions.sort((left, right) -> Double.compare(right.excessBits(), left.excessBits()));

        return new MlExplanation(score, 4.2, attributions, 120, metadata());
    }

    public static ModelMetadata metadata() {
        return new ModelMetadata(20260911L, 150, 120, 120,
                AS_OF, AS_OF, List.of(FeatureVector.NAMES));
    }
}
