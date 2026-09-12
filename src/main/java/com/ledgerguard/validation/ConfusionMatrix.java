package com.ledgerguard.validation;

/**
 * Counts of what the detector said against what the labels say.
 *
 * <h2>Doubles, not integers</h2>
 *
 * Because the counts are <em>weighted</em>. The flagged and audit strata are
 * sampled at completely different rates — every flagged account might be
 * reviewed while one unflagged account in two hundred is — so adding their raw
 * counts would produce a population that does not exist, one where flagged
 * accounts are a third of the ledger. Each labelled account therefore stands for
 * however many it was drawn on behalf of, and those weights are rarely whole
 * numbers.
 *
 * <p>{@link #labelled} keeps the honest sample size alongside, because the
 * weighted counts say what the population looks like and only the raw count says
 * how much evidence there is for it.
 *
 * @param truePositives  flagged, and labelled anomalous
 * @param falsePositives flagged, and labelled benign
 * @param trueNegatives  not flagged, and labelled benign
 * @param falseNegatives not flagged, and labelled anomalous — the ones only an
 *                       audit stratum can ever reveal
 * @param labelled       how many actual accounts went into this, unweighted
 */
public record ConfusionMatrix(
        double truePositives,
        double falsePositives,
        double trueNegatives,
        double falseNegatives,
        long labelled) {

    public static ConfusionMatrix empty() {
        return new ConfusionMatrix(0, 0, 0, 0, 0);
    }

    public ConfusionMatrix plus(ConfusionMatrix other) {
        return new ConfusionMatrix(
                truePositives + other.truePositives,
                falsePositives + other.falsePositives,
                trueNegatives + other.trueNegatives,
                falseNegatives + other.falseNegatives,
                labelled + other.labelled);
    }

    public double flagged() {
        return truePositives + falsePositives;
    }

    public double actualPositives() {
        return truePositives + falseNegatives;
    }

    /** Of what the detector flagged, the share that was anomalous. NaN when it flagged nothing. */
    public double precision() {
        double flagged = flagged();
        return flagged == 0 ? Double.NaN : truePositives / flagged;
    }

    /** Of what was anomalous, the share the detector flagged. NaN when there were none. */
    public double recall() {
        double positives = actualPositives();
        return positives == 0 ? Double.NaN : truePositives / positives;
    }

    public double f1() {
        double precision = precision();
        double recall = recall();
        if (Double.isNaN(precision) || Double.isNaN(recall) || precision + recall == 0) {
            return Double.NaN;
        }
        return 2 * precision * recall / (precision + recall);
    }

    /**
     * The share of the population that is actually anomalous.
     *
     * <p>Published beside every other figure because it is the number that says
     * whether they are impressive. Precision of 0.30 against a base rate of
     * 0.002 is a detector concentrating positives by a factor of 150; the same
     * 0.30 against a base rate of 0.25 is barely better than flagging at random.
     */
    public double baseRate() {
        double total = truePositives + falsePositives + trueNegatives + falseNegatives;
        return total == 0 ? Double.NaN : actualPositives() / total;
    }

    /** How much more likely a flagged account is to be anomalous than an arbitrary one. */
    public double lift() {
        double precision = precision();
        double base = baseRate();
        return Double.isNaN(precision) || Double.isNaN(base) || base == 0
                ? Double.NaN
                : precision / base;
    }
}
