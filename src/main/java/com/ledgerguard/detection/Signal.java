package com.ledgerguard.detection;

/**
 * The five statistical signals, with the numbers that define each one.
 *
 * <h2>Thresholds</h2>
 *
 * Two scales are in play, and each has one flagging point used consistently:
 *
 * <ul>
 *   <li><b>Modified z</b> (amounts): flags at <b>3.5</b>, the Iglewicz–Hoaglin
 *       convention for a MAD-based score.</li>
 *   <li><b>Surprisal</b> (counts and rates): flags at <b>3</b>, meaning the
 *       observation lands in the upper 0.1% of what the baseline predicts.</li>
 * </ul>
 *
 * <h2>Saturation</h2>
 *
 * Every raw statistic is mapped onto {@code [0,1]} by a linear ramp from
 * threshold to saturation. Saturating is not cosmetic: a modified z of 1000, or
 * a surprisal of 40, would otherwise dominate every weighted sum it appeared in
 * and turn the composite into a single-signal score. A smooth logistic would
 * look more sophisticated, but its parameters would be less interpretable and
 * there is nothing yet to calibrate them against.
 *
 * <h2>Weights</h2>
 *
 * <b>These weights are unfitted judgment, not learned parameters.</b> There is no
 * labelled data in this phase, so nothing here has been validated against known
 * fraud. They encode two opinions worth stating: that the reconciliation signal
 * deserves standing because it is the only one grounded in an independent
 * external record rather than in the ledger describing itself, and that the
 * amount signal deserves the most because it is the only one that points at a
 * specific transaction rather than at an account. Fitting them is the ML layer's
 * job.
 */
public enum Signal {

    /** How far an account's largest recent payment sits from its own typical amount. */
    AMOUNT_OUTLIER("amount_outlier", 0.25, 3.5, 10.0),

    /** Whether the account is transacting faster than its own established rate. */
    VELOCITY("velocity", 0.20, 3.0, 8.0),

    /** Whether the account's activity is clustered far more tightly than its rate explains. */
    BURST("burst", 0.20, 3.0, 8.0),

    /** Whether this account's transactions are failing reconciliation unusually often. */
    RECONCILIATION_MISMATCH_RATE("reconciliation_mismatch_rate", 0.20, 3.0, 8.0),

    /** Whether this account's payments are being refunded or reversed unusually often. */
    REFUND_REVERSAL_RATE("refund_reversal_rate", 0.15, 3.0, 8.0);

    private final String wireName;
    private final double weight;
    private final double threshold;
    private final double saturation;

    Signal(String wireName, double weight, double threshold, double saturation) {
        this.wireName = wireName;
        this.weight = weight;
        this.threshold = threshold;
        this.saturation = saturation;
    }

    public String wireName() {
        return wireName;
    }

    /** Relative importance in the composite. Unfitted; see the class note. */
    public double weight() {
        return weight;
    }

    /** Below this the signal scores zero: it has not fired. */
    public double threshold() {
        return threshold;
    }

    /** At or above this the signal scores one; further extremity adds nothing. */
    public double saturation() {
        return saturation;
    }

    /**
     * Map a raw statistic onto {@code [0,1]}.
     *
     * <p>The magnitude is taken, so an amount far <em>below</em> an account's
     * normal is as interesting as one far above. That is deliberate rather than
     * incidental: a run of unusually small payments is what card testing looks
     * like.
     */
    public double normalise(double statistic) {
        double magnitude = Math.abs(statistic);
        if (magnitude <= threshold) {
            return 0.0;
        }
        if (magnitude >= saturation) {
            return 1.0;
        }
        return (magnitude - threshold) / (saturation - threshold);
    }
}
