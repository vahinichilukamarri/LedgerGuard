package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.ml.FeatureVector;

import java.util.Optional;

/**
 * What each model feature corresponds to in the statistical layer, if anything.
 *
 * <h2>Why this has to exist for the disagreement output to mean anything</h2>
 *
 * "The model and the statistics disagree" is a fact about two numbers. The
 * useful version is "the model isolated this account on how long it was dormant,
 * which no statistical signal looks at" — and that sentence is only writable if
 * something knows which features the statistical layer can see. This is that
 * something.
 *
 * <h2>Three kinds of visibility, not two</h2>
 *
 * A binary visible/invisible split would have been wrong in both directions.
 * {@code recentPaymentCount} is not invisible to the statistical layer — the
 * velocity signal counts the same payments — but it is not the same question
 * either: velocity asks whether the count is high <em>for this account</em>, and
 * the raw count is comparable <em>across accounts</em>. Calling that "visible"
 * would suppress a real disagreement; calling it "invisible" would manufacture
 * one. So {@link Visibility#SAME_AXIS} sits between the two, and the narrative
 * only claims a signal sees nothing at all for {@link Visibility#OUTSIDE}.
 *
 * <h2>Drift</h2>
 *
 * This enum restates {@link FeatureVector#NAMES} and the two would be a problem
 * if they parted company, so a test pins them together: same length, same names,
 * same order. Declaring it here rather than on {@code FeatureVector} keeps Phase
 * 9's model input free of an explanation-layer concern.
 */
public enum FeatureProvenance {

    AMOUNT_MODIFIED_Z("amountModifiedZ", Visibility.SIGNAL_STATISTIC, Signal.AMOUNT_OUTLIER,
            "how far the largest recent payment sits from this account's own typical amount"),

    VELOCITY_SURPRISAL("velocitySurprisal", Visibility.SIGNAL_STATISTIC, Signal.VELOCITY,
            "how much faster the account is transacting than its own established rate"),

    BURST_SURPRISAL("burstSurprisal", Visibility.SIGNAL_STATISTIC, Signal.BURST,
            "how tightly the recent payments are clustered in time"),

    MISMATCH_SURPRISAL("mismatchSurprisal", Visibility.SIGNAL_STATISTIC, Signal.RECONCILIATION_MISMATCH_RATE,
            "how often this account's transactions fail reconciliation"),

    RETURN_SURPRISAL("returnSurprisal", Visibility.SIGNAL_STATISTIC, Signal.REFUND_REVERSAL_RATE,
            "how often this account's payments are refunded or reversed"),

    /** The feature Phase 9 added precisely because Phase 8 structurally cannot express it. */
    LOG10_LARGEST_RECENT_AMOUNT("log10LargestRecentAmount", Visibility.OUTSIDE, null,
            "the absolute size of the largest recent payment, which every statistical "
                    + "signal is blind to because they all judge an account against itself"),

    RECENT_PAYMENT_COUNT("recentPaymentCount", Visibility.SAME_AXIS, Signal.VELOCITY,
            "how many payments landed in the recent window, compared across accounts "
                    + "rather than against this account's own rate"),

    LOG10_SECONDS_SINCE_LAST_PAYMENT("log10SecondsSinceLastPayment", Visibility.OUTSIDE, null,
            "how long the account had been dormant before this activity, which no "
                    + "statistical signal covers"),

    HISTORICAL_AMOUNT_PERCENTILE("historicalAmountPercentile", Visibility.SAME_AXIS, Signal.AMOUNT_OUTLIER,
            "where the largest recent payment ranks within this account's own history"),

    RETURNED_PAYMENT_FRACTION("returnedPaymentFraction", Visibility.SAME_AXIS, Signal.REFUND_REVERSAL_RATE,
            "the raw share of this account's recent payments that were refunded or reversed"),

    /**
     * Coverage of the statistical layer as a whole rather than of any one
     * signal, which is why it carries no signal of its own. The statistical
     * explanation publishes the same quantity as {@code applicableSignals}.
     */
    DATA_COMPLETENESS("dataCompleteness", Visibility.SAME_AXIS, null,
            "how much of the statistical layer had enough history to judge this account at all");

    /** How far the statistical layer can see this feature. */
    public enum Visibility {

        /** Literally a signal's own raw statistic, handed to the model unnormalised. */
        SIGNAL_STATISTIC,

        /** A different measurement of a question some signal also asks. */
        SAME_AXIS,

        /** Nothing in the statistical layer asks this question. */
        OUTSIDE
    }

    private final String featureName;
    private final Visibility visibility;
    private final Signal signal;
    private final String description;

    FeatureProvenance(String featureName, Visibility visibility, Signal signal, String description) {
        this.featureName = featureName;
        this.visibility = visibility;
        this.signal = signal;
        this.description = description;
    }

    public String featureName() {
        return featureName;
    }

    public Visibility visibility() {
        return visibility;
    }

    /** The signal on the same axis, where there is one. */
    public Optional<Signal> signal() {
        return Optional.ofNullable(signal);
    }

    /** A phrase that completes "isolated on ...", for the generated summary. */
    public String description() {
        return description;
    }

    /** True only when no statistical signal asks this question at all. */
    public boolean outsideStatisticalLayer() {
        return visibility == Visibility.OUTSIDE;
    }

    public static FeatureProvenance byIndex(int index) {
        return values()[index];
    }

    public static Optional<FeatureProvenance> byName(String featureName) {
        for (FeatureProvenance provenance : values()) {
            if (provenance.featureName.equals(featureName)) {
                return Optional.of(provenance);
            }
        }
        return Optional.empty();
    }
}
