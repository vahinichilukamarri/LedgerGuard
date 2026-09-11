package com.ledgerguard.detection.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The forest itself, against data whose anomaly is known by construction.
 *
 * <p>Everything asserted here is either a formula from the paper checked against
 * arithmetic done by hand, or a structural property the algorithm must have.
 * Nothing is asserted against "whatever the implementation returned", which for
 * a model would be a test that can never fail.
 *
 * <p><b>What this class does not establish.</b> That the model is useful. A
 * forest that separates a point at (12, −11) from a Gaussian cloud has
 * demonstrated it is not broken; it has demonstrated nothing about whether real
 * fraud looks like an outlier in this feature space. That distinction is the
 * whole validation story of Phase 9 and is stated at length in
 * ML_DETECTION_REPORT.md.
 */
class IsolationForestTest {

    /** A reproducible Gaussian cloud. The seed is fixed; nothing here is random at run time. */
    private static List<double[]> gaussianCloud(int count, long seed) {
        Random random = new Random(seed);
        List<double[]> data = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            data.add(new double[]{random.nextGaussian(), random.nextGaussian()});
        }
        return data;
    }

    @Nested
    @DisplayName("c(n), the normalising path length — Equation 1")
    class AveragePathLength {

        @Test
        @DisplayName("is zero for a sample too small to search")
        void degenerateSizes() {
            assertThat(IsolationForest.averagePathLength(0)).isZero();
            assertThat(IsolationForest.averagePathLength(1)).isZero();
        }

        @Test
        @DisplayName("is exactly one for two points")
        void twoPoints() {
            assertThat(IsolationForest.averagePathLength(2)).isEqualTo(1.0);
        }

        /**
         * c(10) = 2(ln 9 + γ) − 2·9/10
         *       = 2(2.1972246 + 0.5772157) − 1.8
         *       = 5.5488806 − 1.8 = 3.7488806
         */
        @Test
        @DisplayName("matches the hand calculation at n = 10")
        void handCalculatedValue() {
            assertThat(IsolationForest.averagePathLength(10))
                    .isCloseTo(3.7488806, within(1e-6));
        }

        @Test
        @DisplayName("grows logarithmically, as a binary search should")
        void growsLogarithmically() {
            double at100 = IsolationForest.averagePathLength(100);
            double at10_000 = IsolationForest.averagePathLength(10_000);

            // Two more doublings of the exponent should roughly double the depth,
            // not multiply it by a hundred.
            assertThat(at10_000 / at100).isCloseTo(2.0, within(0.3));
        }
    }

    @Nested
    @DisplayName("tree construction and path length")
    class Trees {

        @Test
        @DisplayName("an isolated point takes a shorter path than one inside the cloud")
        void isolationIsShallower() {
            List<double[]> data = gaussianCloud(400, 11L);
            double[] anomaly = {12.0, -11.0};
            data.add(anomaly);

            IsolationForest forest = IsolationForest.train(data, 42L, 100, 256);

            assertThat(forest.score(anomaly))
                    .as("a point far outside the cloud must isolate quickly")
                    .isGreaterThan(forest.score(new double[]{0.1, -0.05}));
        }

        /**
         * Identical rows cannot be separated by any split, so the tree must stop
         * rather than recurse forever hunting for one. This is the case the
         * paper does not spell out and the one most likely to hang a naive
         * implementation.
         */
        @Test
        @DisplayName("a sample where every row is identical terminates and scores them alike")
        void constantDataTerminates() {
            List<double[]> data = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                data.add(new double[]{7.0, 7.0, 7.0});
            }

            IsolationForest forest = IsolationForest.train(data, 3L, 20, 32);
            double score = forest.score(new double[]{7.0, 7.0, 7.0});

            assertThat(score).isFinite().isBetween(0.0, 1.0);
            assertThat(forest.score(new double[]{7.0, 7.0, 7.0}))
                    .as("identical inputs must score identically")
                    .isEqualTo(score);
        }

        @Test
        @DisplayName("a constant feature alongside a varying one does not stall the split")
        void partiallyConstantData() {
            List<double[]> data = new ArrayList<>();
            Random random = new Random(5);
            for (int i = 0; i < 200; i++) {
                data.add(new double[]{1.0, random.nextGaussian()});
            }
            double[] anomaly = {1.0, 25.0};
            data.add(anomaly);

            IsolationForest forest = IsolationForest.train(data, 9L, 80, 128);

            assertThat(forest.score(anomaly))
                    .as("the varying feature must still be found and used")
                    .isGreaterThan(forest.score(new double[]{1.0, 0.0}));
        }

        /**
         * Found by this test rather than by reasoning. With a subsample of one,
         * c(ψ) is zero and Equation 2 divides by it, so every score came back
         * NaN — silently, for every input, with nothing in the arithmetic to
         * announce it. A forest over one observation cannot isolate anything, so
         * it is now refused at construction.
         */
        @Test
        @DisplayName("a single training row is refused, rather than scoring everything NaN")
        void singleRowIsRefused() {
            assertThatThrownBy(() ->
                    IsolationForest.train(List.of(new double[]{1.0, 2.0}), 1L, 10, 256))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 2 rows");
        }

        @Test
        @DisplayName("two rows are the smallest forest the arithmetic supports")
        void twoRowsIsTheFloor() {
            IsolationForest forest = IsolationForest.train(
                    List.of(new double[]{1.0, 2.0}, new double[]{9.0, 4.0}), 1L, 10, 256);

            assertThat(forest.subSampleSize()).isEqualTo(2);
            assertThat(forest.score(new double[]{1.0, 2.0}))
                    .as("a score that is NaN is worse than no score, because it propagates")
                    .isFinite()
                    .isBetween(0.0, 1.0);
        }
    }

    @Nested
    @DisplayName("the isolation score — Equation 2")
    class Scoring {

        @Test
        @DisplayName("every score lies in [0,1]")
        void scoresAreBounded() {
            List<double[]> data = gaussianCloud(300, 17L);
            IsolationForest forest = IsolationForest.train(data, 7L, 60, 256);

            for (double[] row : data) {
                assertThat(forest.score(row)).isBetween(0.0, 1.0);
            }
            assertThat(forest.score(new double[]{500, -500})).isBetween(0.0, 1.0);
            assertThat(forest.score(new double[]{0, 0})).isBetween(0.0, 1.0);
        }

        /**
         * s = 2^(−E(h)/c(ψ)), so a point whose expected path length equals the
         * normalising constant must score exactly one half. That is the identity
         * the formula rests on, and it is also why 0.5 is the middle of the
         * distribution rather than a threshold.
         */
        @Test
        @DisplayName("a point of average depth scores one half")
        void averageDepthScoresAHalf() {
            List<double[]> data = gaussianCloud(512, 23L);
            IsolationForest forest = IsolationForest.train(data, 31L, 200, 256);

            double mean = data.stream().mapToDouble(forest::score).average().orElseThrow();

            assertThat(mean)
                    .as("scores concentrate around 0.5 by construction; anyone reading 0.5 "
                            + "as 'half anomalous' is misreading every score this produces")
                    .isCloseTo(0.5, within(0.12));
        }

        @Test
        @DisplayName("the further out a point is, the higher it scores")
        void scoreIncreasesWithIsolation() {
            List<double[]> data = gaussianCloud(400, 29L);
            IsolationForest forest = IsolationForest.train(data, 13L, 150, 256);

            double near = forest.score(new double[]{0.5, 0.5});
            double far = forest.score(new double[]{6, 6});
            double veryFar = forest.score(new double[]{60, 60});

            assertThat(near).isLessThan(far);
            assertThat(far).isLessThanOrEqualTo(veryFar);
        }

        @Test
        @DisplayName("a point of the wrong width is refused rather than silently misread")
        void dimensionIsChecked() {
            IsolationForest forest = IsolationForest.train(gaussianCloud(50, 1L), 1L, 10, 32);

            assertThatThrownBy(() -> forest.score(new double[]{1, 2, 3}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2-feature");
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same seed and data give byte-identical scores")
        void sameSeedSameScores() {
            List<double[]> data = gaussianCloud(300, 41L);
            double[] probe = {4.0, -4.0};

            IsolationForest first = IsolationForest.train(data, 2026L, 120, 256);
            IsolationForest second = IsolationForest.train(data, 2026L, 120, 256);

            assertThat(second.score(probe)).isEqualTo(first.score(probe));
            for (double[] row : data) {
                assertThat(second.score(row)).isEqualTo(first.score(row));
            }
        }

        @Test
        @DisplayName("a different seed gives a different forest")
        void differentSeedDiffers() {
            List<double[]> data = gaussianCloud(300, 41L);
            double[] probe = {2.5, -2.5};

            double one = IsolationForest.train(data, 1L, 120, 256).score(probe);
            double two = IsolationForest.train(data, 2L, 120, 256).score(probe);

            assertThat(one)
                    .as("if the seed did nothing, the determinism test above would prove nothing")
                    .isNotEqualTo(two);
        }

        /**
         * Per-tree seeds are derived by bit mixing rather than by seed + index,
         * because consecutive java.util.Random seeds produce correlated initial
         * output. Correlated trees would shrink the forest's effective size
         * without changing its nominal one.
         */
        @Test
        @DisplayName("neighbouring master seeds produce genuinely different forests")
        void neighbouringSeedsAreNotCorrelated() {
            List<double[]> data = gaussianCloud(300, 41L);
            double[] probe = {3.0, 1.0};

            double first = IsolationForest.train(data, 1000L, 100, 256).score(probe);
            double second = IsolationForest.train(data, 1001L, 100, 256).score(probe);

            assertThat(Math.abs(first - second)).isGreaterThan(1e-9);
        }
    }

    @Nested
    @DisplayName("training guards")
    class Guards {

        @Test
        @DisplayName("an empty training set is refused")
        void emptyData() {
            assertThatThrownBy(() -> IsolationForest.train(List.of(), 1L, 10, 256))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a forest with no trees is refused")
        void noTrees() {
            assertThatThrownBy(() -> IsolationForest.train(gaussianCloud(10, 1L), 1L, 0, 256))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ragged training rows are refused rather than read past the end")
        void raggedRows() {
            List<double[]> ragged = new ArrayList<>(gaussianCloud(10, 1L));
            ragged.add(new double[]{1.0});

            assertThatThrownBy(() -> IsolationForest.train(ragged, 1L, 10, 256))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same length");
        }

        @Test
        @DisplayName("the subsample is capped at the data available")
        void subSampleCapped() {
            IsolationForest forest = IsolationForest.train(gaussianCloud(40, 1L), 1L, 10, 256);

            assertThat(forest.subSampleSize()).isEqualTo(40);
        }
    }
}
