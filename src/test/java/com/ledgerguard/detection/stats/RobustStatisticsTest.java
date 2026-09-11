package com.ledgerguard.detection.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The robust statistics core, including the collapse cases that a naive MAD
 * implementation gets wrong.
 */
class RobustStatisticsTest {

    private static final double DEGENERATE_CEILING = 10.0;

    @Nested
    @DisplayName("median and scale")
    class LocationAndScale {

        @Test
        @DisplayName("the median of an even-length sample averages the middle pair")
        void evenLengthMedian() {
            RobustStatistics.Dispersion dispersion =
                    RobustStatistics.describe(new double[]{1, 2, 3, 4});

            assertThat(dispersion.median()).isEqualTo(2.5);
        }

        @Test
        @DisplayName("MAD is scaled to be comparable with a standard deviation")
        void madIsScaledToSigma() {
            // Deviations from the median of 5 are 4,2,0,2,4; their median is 2.
            RobustStatistics.Dispersion dispersion =
                    RobustStatistics.describe(new double[]{1, 3, 5, 7, 9});

            assertThat(dispersion.median()).isEqualTo(5.0);
            assertThat(dispersion.basis()).isEqualTo(RobustStatistics.ScaleBasis.MAD);
            assertThat(dispersion.sigma())
                    .as("1.4826 x MAD is what makes the scale readable against familiar thresholds")
                    .isCloseTo(2 * RobustStatistics.MAD_TO_SIGMA, within(1e-9));
        }

        @Test
        @DisplayName("an outlier moves the median and MAD barely at all")
        void robustnessToContamination() {
            double[] clean = {10, 10, 11, 11, 12, 12, 13, 13};
            double[] contaminated = {10, 10, 11, 11, 12, 12, 13, 5_000_000};

            RobustStatistics.Dispersion before = RobustStatistics.describe(clean);
            RobustStatistics.Dispersion after = RobustStatistics.describe(contaminated);

            assertThat(after.median()).isCloseTo(before.median(), within(1.0));
            assertThat(after.sigma())
                    .as("this is the whole reason for not using a standard deviation")
                    .isCloseTo(before.sigma(), within(1.0));
        }

        @Test
        @DisplayName("an empty sample is refused rather than silently scored against nothing")
        void emptySampleIsRefused() {
            assertThatThrownBy(() -> RobustStatistics.describe(new double[0]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the degenerate and near-degenerate cases")
    class Collapse {

        /**
         * The case the whole fallback chain exists for, and the one most likely
         * to be broken by a later refactor: an account whose payments are all
         * the same amount. Subscriptions and fixed fees make this ordinary.
         */
        @Test
        @DisplayName("a sample of identical values scores that value at exactly zero")
        void identicalValuesScoreZero() {
            RobustStatistics.Dispersion dispersion =
                    RobustStatistics.describe(new double[]{2500, 2500, 2500, 2500, 2500, 2500});

            assertThat(dispersion.basis()).isEqualTo(RobustStatistics.ScaleBasis.DEGENERATE);
            assertThat(dispersion.isDegenerate()).isTrue();
            assertThat(dispersion.sigma()).isZero();

            assertThat(RobustStatistics.modifiedZ(2500, dispersion, DEGENERATE_CEILING))
                    .as("an account doing exactly what it always does is not anomalous, "
                            + "and must never come back as NaN or infinity")
                    .isZero();
        }

        @Test
        @DisplayName("a different value against a constant history scores the bounded ceiling")
        void differingValueAgainstConstantHistoryIsBounded() {
            RobustStatistics.Dispersion dispersion =
                    RobustStatistics.describe(new double[]{2500, 2500, 2500, 2500, 2500, 2500});

            double z = RobustStatistics.modifiedZ(999_999, dispersion, DEGENERATE_CEILING);

            assertThat(z)
                    .as("different, but by an unmeasurable amount; infinity would let one "
                            + "constant-amount account dominate every composite it appears in")
                    .isEqualTo(DEGENERATE_CEILING);
            assertThat(Double.isFinite(z)).isTrue();
        }

        @Test
        @DisplayName("when MAD collapses but the sample is not constant, the mean deviation takes over")
        void meanAbsoluteDeviationFallback() {
            // Seven identical values put the median and the median of the
            // deviations both at zero, so MAD is unusable while real spread exists.
            double[] values = {100, 100, 100, 100, 100, 100, 100, 200};

            RobustStatistics.Dispersion dispersion = RobustStatistics.describe(values);

            assertThat(dispersion.basis())
                    .isEqualTo(RobustStatistics.ScaleBasis.MEAN_ABSOLUTE_DEVIATION);
            assertThat(dispersion.median()).isEqualTo(100.0);
            assertThat(dispersion.sigma())
                    .isCloseTo(RobustStatistics.MEAN_AD_TO_SIGMA * 12.5, within(1e-9));

            assertThat(RobustStatistics.modifiedZ(200, dispersion, DEGENERATE_CEILING))
                    .as("the outlier is still scored, on a scale that exists")
                    .isCloseTo(100.0 / (RobustStatistics.MEAN_AD_TO_SIGMA * 12.5), within(1e-9));
        }

        /**
         * The boundary between the MAD path and the fallback: exactly half the
         * sample sits off the median, so the median of the deviations is the
         * average of a zero and a non-zero and MAD survives.
         */
        @Test
        @DisplayName("at exactly half the sample identical, MAD survives rather than collapsing")
        void halfIdenticalIsStillMeasurableByMad() {
            double[] values = {100, 100, 100, 100, 120, 140, 160, 180};

            RobustStatistics.Dispersion dispersion = RobustStatistics.describe(values);

            assertThat(dispersion.basis())
                    .as("the fallback must engage only when MAD is genuinely zero")
                    .isEqualTo(RobustStatistics.ScaleBasis.MAD);
            assertThat(dispersion.sigma()).isPositive();
        }

        @Test
        @DisplayName("a single observation is degenerate rather than an error")
        void singleObservation() {
            RobustStatistics.Dispersion dispersion = RobustStatistics.describe(new double[]{42});

            assertThat(dispersion.median()).isEqualTo(42.0);
            assertThat(dispersion.isDegenerate()).isTrue();
            assertThat(RobustStatistics.modifiedZ(42, dispersion, DEGENERATE_CEILING)).isZero();
        }
    }

    @Nested
    @DisplayName("the modified z-score")
    class ModifiedZ {

        @Test
        @DisplayName("a clear outlier scores well past the flagging threshold")
        void obviousOutlier() {
            double[] history = {100, 105, 95, 110, 90, 102, 98, 101};
            RobustStatistics.Dispersion dispersion = RobustStatistics.describe(history);

            assertThat(RobustStatistics.modifiedZ(10_000, dispersion, DEGENERATE_CEILING))
                    .isGreaterThan(3.5);
        }

        @Test
        @DisplayName("an ordinary value scores well inside it")
        void clearNonOutlier() {
            double[] history = {100, 105, 95, 110, 90, 102, 98, 101};
            RobustStatistics.Dispersion dispersion = RobustStatistics.describe(history);

            assertThat(Math.abs(RobustStatistics.modifiedZ(103, dispersion, DEGENERATE_CEILING)))
                    .isLessThan(3.5);
        }

        @Test
        @DisplayName("the score is signed, so unusually small values are as visible as large ones")
        void lowSideIsScoredToo() {
            double[] history = {1000, 1050, 950, 1100, 900, 1020, 980, 1010};
            RobustStatistics.Dispersion dispersion = RobustStatistics.describe(history);

            double low = RobustStatistics.modifiedZ(1, dispersion, DEGENERATE_CEILING);

            assertThat(low).isNegative();
            assertThat(Math.abs(low))
                    .as("card testing looks like a run of trivial amounts, not large ones")
                    .isGreaterThan(3.5);
        }
    }
}
