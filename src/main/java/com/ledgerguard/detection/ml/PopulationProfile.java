package com.ledgerguard.detection.ml;

import java.util.Arrays;
import java.util.List;

/**
 * What each feature looked like across the training population.
 *
 * <h2>Why the attribution is not enough on its own</h2>
 *
 * A split is a threshold and a path is a sequence of left-or-right decisions.
 * Attribution can therefore say <em>which</em> feature isolated a point and say
 * it faithfully, but it structurally cannot say <b>which way</b> — the same
 * feature, the same bits, for an account whose dormancy is extreme in either
 * direction. "Isolated chiefly on {@code log10SecondsSinceLastPayment}" is true
 * and nearly useless to a reviewer; "...which sits at the 99th percentile of the
 * training population" is what they can act on.
 *
 * <p>So this supplies direction, and only direction. It is <b>context, not the
 * model's reasoning</b>, and the explanation layer labels it that way. Ranking
 * features by population extremity alone would have been a third of the work
 * and would have explained a different model — one that looks at each feature in
 * isolation, which a forest does not.
 *
 * <h2>Rank, not an interpolated quantile</h2>
 *
 * The same choice {@code FeatureExtractor.percentileOf} makes, for the same
 * reason: interpolation would imply a precision these sample sizes do not
 * support. A percentile here is the fraction of training rows at or below the
 * value.
 */
public final class PopulationProfile {

    /** One sorted copy of each feature's column. Binary-searched, never mutated. */
    private final double[][] columns;

    private PopulationProfile(double[][] columns) {
        this.columns = columns;
    }

    /** @param rows the exact rows the forest trained on, in feature-index order */
    public static PopulationProfile of(List<double[]> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("a population profile needs at least one row");
        }
        int dimension = rows.get(0).length;
        double[][] columns = new double[dimension][rows.size()];

        for (int row = 0; row < rows.size(); row++) {
            double[] values = rows.get(row);
            if (values.length != dimension) {
                throw new IllegalArgumentException(
                        "every row must have the same length; expected %d, got %d"
                                .formatted(dimension, values.length));
            }
            for (int feature = 0; feature < dimension; feature++) {
                columns[feature][row] = values[feature];
            }
        }
        for (double[] column : columns) {
            Arrays.sort(column);
        }
        return new PopulationProfile(columns);
    }

    /** The fraction of training rows at or below {@code value}, in {@code [0,1]}. */
    public double percentileOf(int feature, double value) {
        double[] column = columns[feature];
        int low = 0;
        int high = column.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (column[middle] <= value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low / (double) column.length;
    }

    /** The population median, averaged across the two middle rows on an even count. */
    public double medianOf(int feature) {
        double[] column = columns[feature];
        int middle = column.length / 2;
        if (column.length % 2 == 1) {
            return column[middle];
        }
        return (column[middle - 1] + column[middle]) / 2.0;
    }

    public int size() {
        return columns[0].length;
    }

    public int dimension() {
        return columns.length;
    }
}
