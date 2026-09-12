package com.ledgerguard.detection.ml;

/**
 * One feature's part in isolating one account, with the population context that
 * makes it readable.
 *
 * <p>The first group of fields is what the model did, read off this point's real
 * paths. The second group is <b>not</b>: a percentile is a fact about the
 * training population, supplied because attribution can name a feature but
 * cannot say which direction it was extreme in. The two are kept as separate
 * fields, and named separately on the wire, so nobody reads the context as the
 * model's reasoning.
 *
 * @param share      this feature's excess bits as a fraction of all positive
 *                   excess bits for this account; zero for a feature that did
 *                   not isolate. A share, not a probability, and it says nothing
 *                   about how much of the <em>score</em> the feature caused
 * @param percentile fraction of training rows at or below this value. Context
 * @param median     the population median of this feature. Context
 */
public record FeatureAttribution(
        String feature,
        int index,
        double value,
        double splitsPerTree,
        double isolationBits,
        double excessBits,
        double share,
        double percentile,
        double median) {

    /** This feature isolated the account faster than an even split would have. */
    public boolean isolating() {
        return excessBits > 0;
    }

    /** Extreme in the population, in the direction the percentile gives. Context only. */
    public boolean extremeInPopulation() {
        return percentile >= 0.95 || percentile <= 0.05;
    }

    /** "above" or "below" the population median; for prose, never for ranking. */
    public String direction() {
        return value >= median ? "above" : "below";
    }
}
