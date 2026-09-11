package com.ledgerguard.detection.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four-way relationship between the two scores.
 *
 * <p>Small, but worth pinning: this enum is the whole mechanism preventing the
 * statistical and learned scores from being quietly averaged into one number, so
 * a later change that collapsed a case would be a change to the phase's central
 * design decision rather than a tidy-up.
 */
class AgreementTest {

    @Test
    @DisplayName("neither elevated")
    void bothQuiet() {
        assertThat(Agreement.of(0.1, 0.4)).isEqualTo(Agreement.BOTH_QUIET);
    }

    @Test
    @DisplayName("both elevated — the only case where the two layers corroborate")
    void bothElevated() {
        assertThat(Agreement.of(0.8, 0.7)).isEqualTo(Agreement.BOTH_ELEVATED);
    }

    @Test
    @DisplayName("statistics only: unusual for this account, ordinary across the population")
    void statisticalOnly() {
        assertThat(Agreement.of(0.9, 0.4)).isEqualTo(Agreement.STATISTICAL_ONLY);
    }

    /**
     * The case the parallel design exists to surface. Folded into a composite
     * this row would vanish beneath accounts the statistics mildly disliked.
     */
    @Test
    @DisplayName("model only: the row worth reading first")
    void mlOnly() {
        assertThat(Agreement.of(0.1, 0.85)).isEqualTo(Agreement.ML_ONLY);
    }

    @Test
    @DisplayName("the thresholds are inclusive at the boundary")
    void boundariesAreInclusive() {
        assertThat(Agreement.of(Agreement.STATISTICAL_ELEVATED, 0.0))
                .isEqualTo(Agreement.STATISTICAL_ONLY);
        assertThat(Agreement.of(0.0, Agreement.ML_ELEVATED))
                .isEqualTo(Agreement.ML_ONLY);
    }

    @Test
    @DisplayName("just below each threshold is quiet")
    void justBelowIsQuiet() {
        assertThat(Agreement.of(Agreement.STATISTICAL_ELEVATED - 1e-9,
                Agreement.ML_ELEVATED - 1e-9))
                .isEqualTo(Agreement.BOTH_QUIET);
    }

    /**
     * The two cut-offs are deliberately different numbers, and a future
     * "simplification" to one shared constant would break the model side: an
     * isolation score sits at 0.5 for a point of average depth, so a 0.5
     * threshold would call roughly half the population elevated.
     */
    @Test
    @DisplayName("the model threshold is higher than the statistical one, on purpose")
    void thresholdsDifferDeliberately() {
        assertThat(Agreement.ML_ELEVATED)
                .as("0.5 is the middle of the isolation score distribution, not a threshold")
                .isGreaterThan(Agreement.STATISTICAL_ELEVATED);

        assertThat(Agreement.of(0.0, 0.55))
                .as("a score just above the isolation midpoint is not yet notable")
                .isEqualTo(Agreement.BOTH_QUIET);
    }
}
