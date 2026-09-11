package com.ledgerguard.detection.stats;

import java.util.Arrays;

/**
 * Location and scale estimates that an outlier cannot corrupt.
 *
 * <h2>Why not mean and standard deviation</h2>
 *
 * Because the thing being hunted is the thing that breaks them. A z-score is
 * {@code (x - mean) / sd}, and both terms are computed from a sample that
 * <em>contains</em> the outlier. One unusually large payment drags the mean
 * toward itself and inflates the standard deviation, often by enough that the
 * payment fails to exceed its own threshold. Statisticians call it masking, and
 * it gets worse as the sample gets smaller — which is exactly the regime a
 * per-account ledger history lives in.
 *
 * <p>The median and the median absolute deviation have a breakdown point of
 * 50%: half the sample can be arbitrarily corrupted before either moves. That
 * is the property worth having here.
 *
 * <h2>The scale estimate</h2>
 *
 * {@code MAD = median(|x - median(x)|)} is not itself comparable to a standard
 * deviation — for normally distributed data it converges to about 0.6745σ. It is
 * multiplied by {@value #MAD_TO_SIGMA} to make it one, which is what turns the
 * result into a number that can be read against familiar thresholds. The
 * Iglewicz–Hoaglin "modified z-score" is usually written
 * {@code 0.6745·(x − median)/MAD}; that is the same quantity, since
 * {@code 0.6745 ≈ 1/1.4826}.
 *
 * <h2>The degenerate case, which is not rare</h2>
 *
 * <b>MAD is zero whenever more than half the sample takes the same value.</b> In
 * a payments ledger that is ordinary rather than exotic: subscriptions, fixed
 * fees and repeated transfers all produce it. A naive implementation divides by
 * zero here and reports every account with a regular payment amount as infinitely
 * anomalous.
 *
 * <p>So the scale falls back in a defined order:
 * <ol>
 *   <li><b>MAD</b>, when it is non-zero — the most robust estimate;</li>
 *   <li><b>mean absolute deviation</b> × {@value #MEAN_AD_TO_SIGMA}, when MAD is
 *       zero but the sample is not constant. Less robust, but it only engages
 *       when the robust estimate has collapsed, and it still beats a standard
 *       deviation because it is linear in the deviations rather than
 *       quadratic;</li>
 *   <li><b>degenerate</b>, when every value is identical. There is no scale to
 *       estimate. A value equal to the others is not anomalous at all and scores
 *       zero; a different value is anomalous but by an unmeasurable amount, so
 *       it is given a bounded maximum rather than infinity.</li>
 * </ol>
 */
public final class RobustStatistics {

    /** Makes MAD consistent with σ for normally distributed data. */
    public static final double MAD_TO_SIGMA = 1.4826;

    /** The same correction for the mean absolute deviation: {@code sqrt(π/2)}. */
    public static final double MEAN_AD_TO_SIGMA = 1.2533141373155003;

    private RobustStatistics() {
    }

    /** Which estimate produced the scale, and therefore how much to trust it. */
    public enum ScaleBasis {
        /** Median absolute deviation. The intended path. */
        MAD,
        /** Mean absolute deviation, because MAD collapsed to zero. */
        MEAN_ABSOLUTE_DEVIATION,
        /** Every observation is identical; there is no dispersion to measure. */
        DEGENERATE
    }

    /**
     * A robust summary of one sample.
     *
     * @param median the robust centre
     * @param sigma  a scale comparable to a standard deviation, or zero when
     *               {@code basis} is {@link ScaleBasis#DEGENERATE}
     */
    public record Dispersion(double median, double sigma, ScaleBasis basis, int sampleSize) {

        public boolean isDegenerate() {
            return basis == ScaleBasis.DEGENERATE;
        }
    }

    /**
     * @throws IllegalArgumentException if {@code values} is empty; an empty
     *                                  sample has no centre, and returning a
     *                                  silent zero would let a caller score
     *                                  against nothing
     */
    public static Dispersion describe(double[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("cannot describe an empty sample");
        }

        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double median = medianOfSorted(sorted);

        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(values[i] - median);
        }
        Arrays.sort(deviations);

        double mad = medianOfSorted(deviations);
        if (mad > 0) {
            return new Dispersion(median, MAD_TO_SIGMA * mad, ScaleBasis.MAD, values.length);
        }

        // MAD collapsed: more than half the sample sits exactly on the median.
        double meanAbsoluteDeviation = 0;
        for (double deviation : deviations) {
            meanAbsoluteDeviation += deviation;
        }
        meanAbsoluteDeviation /= deviations.length;

        if (meanAbsoluteDeviation > 0) {
            return new Dispersion(median, MEAN_AD_TO_SIGMA * meanAbsoluteDeviation,
                    ScaleBasis.MEAN_ABSOLUTE_DEVIATION, values.length);
        }

        return new Dispersion(median, 0.0, ScaleBasis.DEGENERATE, values.length);
    }

    /**
     * How many robust standard deviations {@code value} sits from the centre.
     *
     * <p>In the degenerate case the answer is either exactly zero or
     * {@code degenerateCeiling}: with no dispersion to measure there is no
     * meaningful magnitude, only "the same" or "different", and returning
     * infinity would let one constant-amount account dominate every composite
     * score it appears in.
     *
     * @param degenerateCeiling what a differing value scores when the sample has
     *                          no dispersion at all
     */
    public static double modifiedZ(double value, Dispersion dispersion, double degenerateCeiling) {
        if (dispersion.isDegenerate()) {
            return value == dispersion.median() ? 0.0 : degenerateCeiling;
        }
        return (value - dispersion.median()) / dispersion.sigma();
    }

    /** The median of an already-sorted array. Even lengths average the middle pair. */
    private static double medianOfSorted(double[] sorted) {
        int middle = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[middle];
        }
        return (sorted[middle - 1] + sorted[middle]) / 2.0;
    }
}
