package com.ledgerguard.detection.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Taking an isolation score apart by feature.
 *
 * <h2>Why these tests are the ones that matter</h2>
 *
 * An attribution method cannot be checked against a ground truth the way a
 * score can be checked against a formula — there is no correct answer to
 * "which feature isolated this point", only more and less faithful accounts of
 * it. What <em>can</em> be pinned is the case where the answer is not in doubt:
 * a population that is ordinary on every axis, and one point pushed far out
 * along exactly one of them. If the method cannot name that feature, it cannot
 * be trusted on the cases where the answer is unclear.
 *
 * <p>So the central test runs that construction once per feature, all eleven,
 * rather than once on a convenient one. Nothing here depends on a real clock, a
 * database or an unseeded generator, so there is nothing to be flaky about.
 */
class AttributionTest {

    private static final long POPULATION_SEED = 20260912L;
    private static final long FOREST_SEED = 4242L;
    private static final int POPULATION = 300;
    private static final int TREES = 150;
    private static final int SUBSAMPLE = 256;

    /**
     * An ordinary population: every feature standard normal and independent, so
     * no axis is special until a test makes one so.
     */
    private static List<double[]> ordinaryPopulation() {
        Random random = new Random(POPULATION_SEED);
        List<double[]> rows = new ArrayList<>(POPULATION);
        for (int row = 0; row < POPULATION; row++) {
            double[] values = new double[FeatureVector.dimension()];
            for (int feature = 0; feature < values.length; feature++) {
                values[feature] = random.nextGaussian();
            }
            rows.add(values);
        }
        return rows;
    }

    private static IsolationForest forest() {
        return IsolationForest.train(ordinaryPopulation(), FOREST_SEED, TREES, SUBSAMPLE);
    }

    /** Ordinary on every axis except {@code feature}, which is far out. */
    private static double[] extremeOn(int feature) {
        double[] point = new double[FeatureVector.dimension()];
        point[feature] = 40.0;
        return point;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    @DisplayName("a point extreme on one feature is attributed to that feature, by name")
    void attributesToTheFeatureThatIsolated(int feature) {
        IsolationAttribution attribution = forest().attribute(extremeOn(feature));

        List<FeatureCredit> ranked = new ArrayList<>(attribution.credits());
        ranked.sort(Comparator.comparingDouble(FeatureCredit::excessBits).reversed());

        assertThat(ranked.get(0).index())
                .as("the only unusual axis must be the one named, not merely be in the list")
                .isEqualTo(feature);
        assertThat(ranked.get(0).name()).isEqualTo(FeatureVector.NAMES[feature]);
        assertThat(ranked.get(0).excessBits()).isPositive();
        assertThat(ranked.get(1).excessBits())
                .as("and nothing else should look like it isolated the point")
                .isNotPositive();
    }

    @Test
    @DisplayName("a point in the middle of the population is attributed to nothing")
    void anOrdinaryPointIsolatesOnNothing() {
        IsolationAttribution attribution = forest().attribute(new double[FeatureVector.dimension()]);

        assertThat(attribution.credits())
                .as("no feature separated it faster than an even split would have")
                .allSatisfy(credit -> assertThat(credit.excessBits()).isNotPositive());
    }

    @Test
    @DisplayName("the explained score is the published score, not a second computation of it")
    void scoreIsNotRecomputed() {
        IsolationForest forest = forest();
        double[] point = extremeOn(5);

        assertThat(forest.attribute(point).score())
                .as("an explanation that disagreed with the number it explains would be worse "
                        + "than no explanation")
                .isEqualTo(forest.score(point));
    }

    @Test
    @DisplayName("the reported path length is the one the score implies")
    void expectedPathLengthMatchesTheScore() {
        IsolationAttribution attribution = forest().attribute(extremeOn(3));

        double implied = Math.pow(2,
                -attribution.expectedPathLength() / IsolationForest.averagePathLength(SUBSAMPLE));

        assertThat(implied).isCloseTo(attribution.score(), within(1e-12));
    }

    @Test
    @DisplayName("attribution is deterministic, like everything else in the detection layer")
    void deterministic() {
        double[] point = extremeOn(7);

        IsolationAttribution first = forest().attribute(point);
        IsolationAttribution second = forest().attribute(point);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("bits are never negative, and excess bits are bits less one per split crossed")
    void creditRuleHolds() {
        IsolationAttribution attribution = forest().attribute(extremeOn(2));

        assertThat(attribution.credits()).allSatisfy(credit -> {
            assertThat(credit.isolationBits()).isGreaterThanOrEqualTo(0.0);
            assertThat(credit.splitsPerTree()).isGreaterThanOrEqualTo(0.0);
            assertThat(credit.excessBits())
                    .isCloseTo(credit.isolationBits() - credit.splitsPerTree(), within(1e-12));
        });
    }

    @Test
    @DisplayName("a feature no split touched earns nothing at all, rather than a small something")
    void untouchedFeaturesEarnNothing() {
        // A population constant on one feature: no tree can split on it, because
        // the builder only selects attributes that vary in the subsample.
        List<double[]> rows = ordinaryPopulation();
        for (double[] row : rows) {
            row[9] = 1.0;
        }
        IsolationForest forest = IsolationForest.train(rows, FOREST_SEED, TREES, SUBSAMPLE);

        FeatureCredit constant = forest.attribute(extremeOn(4)).credits().get(9);

        assertThat(constant.splitsPerTree()).isZero();
        assertThat(constant.isolationBits()).isZero();
        assertThat(constant.excessBits()).isZero();
    }

    // ------------------------------------------------ the explanation wrapper

    @Test
    @DisplayName("shares are taken over the isolating features only, and sum to one")
    void sharesSumToOneAcrossDrivers() {
        List<double[]> rows = ordinaryPopulation();
        IsolationForest forest = IsolationForest.train(rows, FOREST_SEED, TREES, SUBSAMPLE);
        double[] point = extremeOn(6);

        MlExplanation explanation = MlExplanation.of(
                forest.attribute(point), point, PopulationProfile.of(rows), metadata());

        assertThat(explanation.drivers()).isNotEmpty();
        assertThat(explanation.drivers().stream().mapToDouble(FeatureAttribution::share).sum())
                .isCloseTo(1.0, within(1e-12));
        assertThat(explanation.attributions())
                .as("every feature is reported, including the ones that isolated nothing")
                .hasSize(FeatureVector.dimension());
        assertThat(explanation.attributions().stream()
                .filter(attribution -> !attribution.isolating()))
                .allSatisfy(attribution -> assertThat(attribution.share()).isZero());
    }

    @Test
    @DisplayName("the driver carries the population context that says which way it was extreme")
    void driversCarryDirection() {
        List<double[]> rows = ordinaryPopulation();
        IsolationForest forest = IsolationForest.train(rows, FOREST_SEED, TREES, SUBSAMPLE);
        double[] point = extremeOn(5);

        MlExplanation explanation = MlExplanation.of(
                forest.attribute(point), point, PopulationProfile.of(rows), metadata());
        FeatureAttribution driver = explanation.drivers().get(0);

        assertThat(driver.feature()).isEqualTo("log10LargestRecentAmount");
        assertThat(driver.value()).isEqualTo(40.0);
        assertThat(driver.percentile())
                .as("attribution names the feature; only the population says which way")
                .isEqualTo(1.0);
        assertThat(driver.direction()).isEqualTo("above");
        assertThat(driver.extremeInPopulation()).isTrue();
        assertThat(explanation.trainingRows()).isEqualTo(POPULATION);
    }

    @Test
    @DisplayName("drivers are ordered strongest first and exclude the features that did nothing")
    void driversAreRankedAndFiltered() {
        List<double[]> rows = ordinaryPopulation();
        IsolationForest forest = IsolationForest.train(rows, FOREST_SEED, TREES, SUBSAMPLE);
        double[] point = extremeOn(1);

        MlExplanation explanation = MlExplanation.of(
                forest.attribute(point), point, PopulationProfile.of(rows), metadata());

        assertThat(explanation.attributions()).isSortedAccordingTo(
                Comparator.comparingDouble(FeatureAttribution::excessBits).reversed());
        assertThat(explanation.drivers())
                .allSatisfy(driver -> assertThat(driver.excessBits()).isPositive());
        assertThat(explanation.topDrivers(2)).hasSizeLessThanOrEqualTo(2);
        assertThat(explanation.topDrivers(2).get(0).feature()).isEqualTo("velocitySurprisal");
    }

    private static ModelMetadata metadata() {
        return new ModelMetadata(FOREST_SEED, TREES, SUBSAMPLE, POPULATION,
                java.time.Instant.parse("2026-09-12T10:00:00Z"),
                java.time.Instant.parse("2026-09-12T10:00:00Z"),
                List.of(FeatureVector.NAMES));
    }
}
