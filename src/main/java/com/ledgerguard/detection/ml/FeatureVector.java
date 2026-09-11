package com.ledgerguard.detection.ml;

/**
 * The fixed-length numeric vector an Isolation Forest sees.
 *
 * <h2>Raw statistics, not normalised scores</h2>
 *
 * The first five features are Phase 8's <em>raw</em> statistics — a modified
 * z-score, four surprisals — and deliberately not the {@code [0,1]} scores the
 * statistical layer publishes. {@code Signal.normalise} clamps everything below
 * its threshold to zero and everything above saturation to one, which is exactly
 * right for a human-readable composite and exactly wrong as model input: it
 * destroys the ordering at both ends, which is most of the information. An
 * account at surprisal 4 and one at surprisal 40 are the same number after
 * normalisation and very different rows here.
 *
 * <p>It is the same principle as Phase 8's decision to compute at query time
 * rather than from a stored aggregate: do not hand the next layer a version of
 * the data that has already had its information removed.
 *
 * <h2>Scaling</h2>
 *
 * None, and none needed. An Isolation Forest splits uniformly between the
 * minimum and maximum of a feature <em>within the subsample</em>, so it is
 * scale-invariant by construction — no standardisation, no unit variance, and
 * none of the bugs that come with them.
 *
 * <p>It is not, however, <em>shape</em>-invariant. A feature spanning orders of
 * magnitude puts almost every uniform split in its sparse tail, which wastes
 * splits and makes the forest insensitive to structure at the dense end. That is
 * why the amount and elapsed-time features are log-transformed and the raw ones
 * are not.
 *
 * <h2>Imputation</h2>
 *
 * A Phase 8 statistic is {@code NaN} when its signal had too little data to
 * judge, and a forest needs a number. Those are imputed as zero — "no evidence
 * of anomaly on this axis" — which deliberately conflates "measured, and
 * unremarkable" with "not measurable". {@link #dataCompleteness} exists to make
 * that conflation visible to the model rather than silent: it is the fraction of
 * signals that had anything to say, so the forest can learn that thin-history
 * accounts are their own population instead of isolating on an artefact of the
 * imputation.
 *
 * <p><b>This does not solve the problem, it only exposes it.</b> If most of the
 * training population has thin data, the imputed zeros dominate and the model
 * learns the imputation rather than the behaviour. See ML_DETECTION_REPORT.md.
 */
public record FeatureVector(
        /* --- Phase 8 statistics, reused rather than recomputed --- */

        /**
         * Signed, not absolute. Direction is information: a payment far
         * <em>below</em> an account's usual is card testing, one far above is
         * something else, and they are different behaviours the forest can learn
         * separately. Phase 8's own score takes the magnitude because it needs
         * one number for a human; the model has no such constraint.
         */
        double amountModifiedZ,
        double velocitySurprisal,
        double burstSurprisal,
        double mismatchSurprisal,
        double returnSurprisal,

        /* --- raw features the statistical layer structurally cannot express --- */

        /**
         * The one thing Phase 8 cannot see. Every statistical signal is relative
         * to the account's own history — there is deliberately no absolute
         * amount threshold anywhere in that layer. A forest trains across the
         * whole population, so it can learn that some magnitudes are rare
         * <em>everywhere</em>, which is a genuinely different question from
         * whether they are rare for this account.
         */
        double log10LargestRecentAmount,

        /** Cross-account comparable, where velocity surprisal is relative to this account's own rate. */
        double recentPaymentCount,

        /**
         * Dormancy followed by activity, which no Phase 8 signal covers: velocity
         * sees rate and burst sees clustering, but neither notices an account
         * that has been silent for six months and just woke up.
         */
        double log10SecondsSinceLastPayment,

        /**
         * Where the largest recent amount falls in the account's own history, as
         * a rank in {@code [0,1]}. Related to the modified z but not redundant:
         * this is bounded, assumes no distribution shape at all, and still
         * separates "largest ever" from "merely large" when the z-score has
         * saturated or its scale estimate has collapsed.
         */
        double historicalAmountPercentile,

        /** This account's own return rate, where the surprisal is against the population's. */
        double returnedPaymentFraction,

        /** Fraction of the five signals that had enough data to judge. See the class note. */
        double dataCompleteness) {

    /**
     * Feature order, fixed in one place. The forest addresses features by index,
     * so this array and {@link #toArray()} must not drift apart — they are
     * defined next to each other for that reason, and a test pins the length.
     */
    public static final String[] NAMES = {
            "amountModifiedZ",
            "velocitySurprisal",
            "burstSurprisal",
            "mismatchSurprisal",
            "returnSurprisal",
            "log10LargestRecentAmount",
            "recentPaymentCount",
            "log10SecondsSinceLastPayment",
            "historicalAmountPercentile",
            "returnedPaymentFraction",
            "dataCompleteness"
    };

    public static int dimension() {
        return NAMES.length;
    }

    /** In {@link #NAMES} order. */
    public double[] toArray() {
        return new double[]{
                amountModifiedZ,
                velocitySurprisal,
                burstSurprisal,
                mismatchSurprisal,
                returnSurprisal,
                log10LargestRecentAmount,
                recentPaymentCount,
                log10SecondsSinceLastPayment,
                historicalAmountPercentile,
                returnedPaymentFraction,
                dataCompleteness
        };
    }
}
