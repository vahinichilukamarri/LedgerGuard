package com.ledgerguard.validation;

import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.ml.Agreement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A structural property of Phase 8's composite, found by measuring it.
 *
 * <h2>What the benchmark turned up</h2>
 *
 * On the synthetic population the statistical layer recalled 1 anomaly in 10 at
 * its own threshold, while the isolation score recalled 5. That gap is not
 * noise and not a property of the test data — it is arithmetic, and it holds for
 * every account this system will ever score.
 *
 * <p>The composite is a weighted mean over the <em>applicable</em> signals. One
 * signal at 1.00 therefore contributes its own renormalised weight and nothing
 * more. With all five signals applicable the weights sum to 1.0, so a single
 * firing signal produces a composite equal to its weight — and the heaviest
 * weight in the system is {@code AMOUNT_OUTLIER} at 0.25, which is half the
 * 0.50 elevation threshold.
 *
 * <p><b>No single signal, however extreme, can flag a fully-measured account.</b>
 * An account whose amounts are twelve thousand robust deviations from its own
 * history scores 0.25 and is not elevated.
 *
 * <p>Nor can any pair. The two heaviest weights are 0.25 and 0.20, which sum to
 * 0.45. Writing this test is what turned that up: the first version asserted
 * that two signals suffice, and it failed. <b>Three of the five signals must
 * fire at saturation before a fully-measured account is elevated at all</b>,
 * and the weakest three still clear it, so the bar is exactly three.
 *
 * <h2>The perverse part</h2>
 *
 * Phase 8 documented the renormalisation trade and framed it as protection for
 * thin-history accounts: "an account with only two measurable signals could
 * never exceed 0.45 however extreme its behaviour". The measured consequence is
 * the mirror image of the intended one. A thin-history account, where only two
 * signals apply, needs <em>one</em> of them to fire to clear 0.5. A
 * well-evidenced account, where all five apply, needs three. The composite is
 * easier to trip with less evidence, not harder.
 *
 * <h2>This test changes nothing</h2>
 *
 * Phase 12 measures and does not tune, so the weights and the threshold are
 * untouched. This exists to pin the property so that a later phase which does
 * move them has to confront it deliberately, and so the figure in
 * VALIDATION_REPORT.md has something that fails if the arithmetic stops being
 * true.
 */
class CompositeCeilingTest {

    private static final double TOTAL_WEIGHT =
            Arrays.stream(Signal.values()).mapToDouble(Signal::weight).sum();

    @Test
    @DisplayName("the five weights sum to one, so a lone signal contributes exactly its weight")
    void weightsSumToOne() {
        assertThat(TOTAL_WEIGHT).isCloseTo(1.0, within(1e-12));
    }

    @ParameterizedTest
    @EnumSource(Signal.class)
    @DisplayName("no single signal can elevate an account whose five signals all apply")
    void noSingleSignalCanFlagAFullyMeasuredAccount(Signal signal) {
        // Every signal applicable, this one saturated, the rest silent.
        double composite = signal.weight() / TOTAL_WEIGHT;

        assertThat(composite)
                .as("%s at full strength reaches only %.2f against a threshold of %.2f",
                        signal.wireName(), composite, Agreement.STATISTICAL_ELEVATED)
                .isLessThan(Agreement.STATISTICAL_ELEVATED);
    }

    @Test
    @DisplayName("the heaviest signal reaches half the threshold, so the gap is not marginal")
    void theGapIsNotMarginal() {
        double heaviest = Arrays.stream(Signal.values())
                .mapToDouble(Signal::weight)
                .max()
                .orElseThrow();

        assertThat(heaviest / TOTAL_WEIGHT).isCloseTo(0.25, within(1e-12));
        assertThat(heaviest / TOTAL_WEIGHT)
                .as("no amount of tuning one signal's sensitivity closes a 2x gap")
                .isLessThan(Agreement.STATISTICAL_ELEVATED / 1.9);
    }

    /**
     * The mirror image of the trade Phase 8 documented. Same renormalisation,
     * and the direction nobody wrote down.
     */
    @Test
    @DisplayName("a thin-history account is easier to elevate than a fully-measured one")
    void thinEvidenceIsEasierToFlag() {
        // Only the amount and velocity signals could judge.
        double thinWeight = Signal.AMOUNT_OUTLIER.weight() + Signal.VELOCITY.weight();
        double thinComposite = Signal.AMOUNT_OUTLIER.weight() / thinWeight;

        double measuredComposite = Signal.AMOUNT_OUTLIER.weight() / TOTAL_WEIGHT;

        assertThat(thinComposite)
                .as("two payments of history and one firing signal clears the bar")
                .isGreaterThanOrEqualTo(Agreement.STATISTICAL_ELEVATED);
        assertThat(measuredComposite)
                .as("a long history and the same firing signal does not")
                .isLessThan(Agreement.STATISTICAL_ELEVATED);
    }

    /**
     * The headline number of the phase, as arithmetic rather than as a
     * measurement that might have gone another way.
     */
    @Test
    @DisplayName("a fully-measured account needs three of five signals at saturation")
    void threeSignalsAreRequired() {
        double[] descending = Arrays.stream(Signal.values())
                .mapToDouble(Signal::weight)
                .boxed()
                .sorted(java.util.Comparator.reverseOrder())
                .mapToDouble(Double::doubleValue)
                .toArray();

        double bestTwo = (descending[0] + descending[1]) / TOTAL_WEIGHT;
        assertThat(bestTwo)
                .as("even the two heaviest signals, both saturated, fall short")
                .isLessThan(Agreement.STATISTICAL_ELEVATED);

        double[] ascending = Arrays.stream(Signal.values())
                .mapToDouble(Signal::weight)
                .sorted()
                .toArray();
        double weakestThree = (ascending[0] + ascending[1] + ascending[2]) / TOTAL_WEIGHT;
        assertThat(weakestThree)
                .as("while even the three lightest clear it, so the bar is exactly three")
                .isGreaterThanOrEqualTo(Agreement.STATISTICAL_ELEVATED);

        assertThat(minimumSignalsToElevate())
                .as("three of five, for every account the detector fully measures")
                .isEqualTo(3);
    }

    /** How many saturated signals it takes, in the most favourable order. */
    private static int minimumSignalsToElevate() {
        double[] descending = Arrays.stream(Signal.values())
                .mapToDouble(Signal::weight)
                .boxed()
                .sorted(java.util.Comparator.reverseOrder())
                .mapToDouble(Double::doubleValue)
                .toArray();

        double running = 0;
        for (int count = 0; count < descending.length; count++) {
            running += descending[count];
            if (running / TOTAL_WEIGHT >= Agreement.STATISTICAL_ELEVATED) {
                return count + 1;
            }
        }
        return descending.length;
    }
}
