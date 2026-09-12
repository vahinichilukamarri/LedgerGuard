package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.FeatureAttribution;
import com.ledgerguard.detection.ml.MlExplanation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * How the two layers' explanations relate, stated rather than resolved.
 *
 * <h2>The thing this must not do</h2>
 *
 * The easy output here is one narrative per account, written so smoothly that
 * two layers disagreeing reads like two layers agreeing. That would undo Phase
 * 9's central decision at the presentation layer: the scores were kept apart
 * precisely because disagreement is the most informative thing the pair
 * produces, and a summary that averages the <em>prose</em> loses exactly what
 * refusing to average the <em>numbers</em> preserved.
 *
 * <p>So the four {@link Agreement} states get four different sentences, and two
 * of them say outright that the layers are looking at different things. The
 * hardest case is not the disagreement — it is {@code BOTH_ELEVATED} where the
 * two are elevated for unrelated reasons, which looks like corroboration in the
 * scores and is not. {@link #corroborated} separates those, and the narrative
 * says so in words.
 *
 * @param corroboratedSignals     signals that fired statistically <em>and</em>
 *                                sit on the axis of a feature that isolated the
 *                                account. The only real agreement available
 * @param modelDriversOutsideView drivers no statistical signal asks about at all
 * @param corroborated            whether the layers point at any shared axis.
 *                                False alongside {@code BOTH_ELEVATED} is the
 *                                case worth reading carefully
 */
public record Reconciliation(
        Agreement agreement,
        List<String> statisticalDrivers,
        List<String> modelDrivers,
        List<String> corroboratedSignals,
        List<String> modelDriversOutsideView,
        boolean corroborated,
        String narrative) {

    /** How many drivers per layer a narrative names before it stops being readable. */
    private static final int NAMED_DRIVERS = 3;

    public Reconciliation {
        statisticalDrivers = List.copyOf(statisticalDrivers);
        modelDrivers = List.copyOf(modelDrivers);
        corroboratedSignals = List.copyOf(corroboratedSignals);
        modelDriversOutsideView = List.copyOf(modelDriversOutsideView);
    }

    public static Reconciliation of(StatisticalExplanation statistical, MlExplanation ml) {
        Agreement agreement = Agreement.of(statistical.composite(), ml.score());

        List<SignalContribution> firedSignals = statistical.drivers();
        List<String> statisticalDrivers = firedSignals.stream()
                .limit(NAMED_DRIVERS)
                .map(SignalContribution::signal)
                .toList();

        List<FeatureAttribution> isolating = ml.topDrivers(NAMED_DRIVERS);
        List<String> modelDrivers = isolating.stream().map(FeatureAttribution::feature).toList();

        Set<String> fired = new LinkedHashSet<>(
                firedSignals.stream().map(SignalContribution::signal).toList());

        List<String> corroboratedSignals = new ArrayList<>();
        List<String> outsideView = new ArrayList<>();
        for (FeatureAttribution driver : isolating) {
            FeatureProvenance provenance = FeatureProvenance.byIndex(driver.index());
            if (provenance.outsideStatisticalLayer()) {
                outsideView.add(driver.feature());
                continue;
            }
            provenance.signal()
                    .map(Signal::wireName)
                    .filter(fired::contains)
                    .filter(name -> !corroboratedSignals.contains(name))
                    .ifPresent(corroboratedSignals::add);
        }

        boolean corroborated = !corroboratedSignals.isEmpty();

        return new Reconciliation(
                agreement,
                statisticalDrivers,
                modelDrivers,
                corroboratedSignals,
                outsideView,
                corroborated,
                narrate(agreement, statistical, ml, isolating, corroboratedSignals, corroborated));
    }

    // ------------------------------------------------------------ narration

    private static String narrate(Agreement agreement,
                                  StatisticalExplanation statistical,
                                  MlExplanation ml,
                                  List<FeatureAttribution> isolating,
                                  List<String> corroboratedSignals,
                                  boolean corroborated) {

        String signals = join(statistical.drivers().stream()
                .limit(NAMED_DRIVERS)
                .map(SignalContribution::signal)
                .toList());
        String features = describeDrivers(isolating);

        return switch (agreement) {
            case BOTH_ELEVATED -> corroborated
                    ? ("Both layers are elevated and they point at the same behaviour: %s fired "
                    + "statistically, and the model isolated this account chiefly on %s. The shared "
                    + "axis is %s.")
                    .formatted(signals, features, join(corroboratedSignals))
                    : ("Both layers are elevated, but not on the same evidence. The composite rests "
                    + "on %s, while the model isolated this account on %s. Agreeing that something "
                    + "is unusual is not the same as agreeing on what, and nothing here corroborates "
                    + "anything.")
                    .formatted(signals, features);

            case STATISTICAL_ONLY -> ("The statistical layer is elevated and the model is not. %s "
                    + "fired against this account's own history, but at %.2f the isolation score is "
                    + "below the %.2f convention: across the %d accounts the model was trained on, "
                    + "behaviour like this is not rare enough to isolate. Unusual for this account "
                    + "and ordinary for the population is the ordinary reading; the other reading is "
                    + "that the model's features do not capture what the signals caught.")
                    .formatted(capitalise(signals), ml.score(), Agreement.ML_ELEVATED, ml.trainingRows());

            case ML_ONLY -> ("The model is elevated and the statistical layer is not. It isolated "
                    + "this account on %s, while the composite of %.2f rests on %s. %s")
                    .formatted(features, statistical.composite(),
                            statistical.drivers().isEmpty() ? "no signal at all" : signals,
                            outsideNote(isolating));

            case BOTH_QUIET -> ("Neither layer is elevated: a composite of %.2f over %d applicable "
                    + "signal(s) and an isolation score of %.2f, where roughly 0.5 is the middle of "
                    + "the distribution rather than a threshold. Quiet in both layers is the weakest "
                    + "claim either can make, not a clean bill of health.")
                    .formatted(statistical.composite(), statistical.applicableSignals(), ml.score());
        };
    }

    /**
     * The ML_ONLY case is the one Phase 9 built the parallel score to surface,
     * so it says explicitly whether the model found something the signals
     * structurally cannot see, or merely disagreed on an axis they share.
     */
    private static String outsideNote(List<FeatureAttribution> isolating) {
        List<FeatureProvenance> outside = isolating.stream()
                .map(driver -> FeatureProvenance.byIndex(driver.index()))
                .filter(FeatureProvenance::outsideStatisticalLayer)
                .toList();

        if (outside.isEmpty()) {
            return "Every axis the model isolated on is one some statistical signal also looks at, "
                    + "so the two layers are disagreeing about the same evidence rather than seeing "
                    + "different evidence.";
        }
        return ("The statistical layer does not see %s at all, so there is nothing in the composite "
                + "to corroborate or contradict this.")
                .formatted(join(outside.stream().map(FeatureProvenance::featureName).toList()));
    }

    private static String describeDrivers(List<FeatureAttribution> isolating) {
        if (isolating.isEmpty()) {
            return "no feature in particular — nothing isolated it faster than an even split would have";
        }
        List<String> described = isolating.stream()
                .map(driver -> "%s (%.0f%% of the isolation, %s the population median at the %s percentile)"
                        .formatted(driver.feature(), driver.share() * 100, driver.direction(),
                                ordinal(driver.percentile())))
                .toList();
        return join(described);
    }

    private static String ordinal(double percentile) {
        long rank = Math.round(percentile * 100);
        String suffix = switch ((int) (rank % 100)) {
            case 11, 12, 13 -> "th";
            default -> switch ((int) (rank % 10)) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        };
        return rank + suffix;
    }

    private static String join(List<String> parts) {
        if (parts.isEmpty()) {
            return "nothing";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** The signal a model driver shares an axis with, where there is one. */
    public static Optional<Signal> axisOf(FeatureAttribution attribution) {
        return FeatureProvenance.byIndex(attribution.index()).signal();
    }
}
