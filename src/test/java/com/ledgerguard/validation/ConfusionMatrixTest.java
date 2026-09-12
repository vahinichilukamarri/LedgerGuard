package com.ledgerguard.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The counts, and what they refuse to say.
 *
 * <p>Every undefined quantity comes back {@code NaN} rather than zero. A
 * detector that flagged nothing has no precision; reporting that as 0.0 would
 * describe a detector that flagged everything wrongly, which is the opposite
 * claim.
 */
class ConfusionMatrixTest {

    @Test
    @DisplayName("precision, recall and F1 on a worked example")
    void metrics() {
        ConfusionMatrix confusion = new ConfusionMatrix(8, 2, 85, 5, 100);

        assertThat(confusion.precision()).isCloseTo(0.8, within(1e-12));
        assertThat(confusion.recall()).isCloseTo(8.0 / 13.0, within(1e-12));
        assertThat(confusion.f1()).isCloseTo(
                2 * 0.8 * (8.0 / 13.0) / (0.8 + 8.0 / 13.0), within(1e-12));
    }

    @Test
    @DisplayName("a detector that flagged nothing has no precision, rather than zero")
    void undefinedPrecision() {
        ConfusionMatrix confusion = new ConfusionMatrix(0, 0, 90, 10, 100);

        assertThat(confusion.precision()).isNaN();
        assertThat(confusion.recall()).isZero();
        assertThat(confusion.f1()).isNaN();
    }

    @Test
    @DisplayName("a population with no anomalies has no recall, rather than zero")
    void undefinedRecall() {
        ConfusionMatrix confusion = new ConfusionMatrix(0, 5, 95, 0, 100);

        assertThat(confusion.recall()).isNaN();
        assertThat(confusion.precision()).isZero();
    }

    /**
     * The number that decides whether the others are impressive. Precision of
     * 0.30 against a base rate of 0.002 is a detector concentrating positives
     * 150-fold; the same figure against a base rate of 0.25 is barely better
     * than flagging at random.
     */
    @Test
    @DisplayName("base rate and lift put precision in context")
    void baseRateAndLift() {
        ConfusionMatrix rare = new ConfusionMatrix(3, 7, 9980, 10, 200);

        assertThat(rare.baseRate()).isCloseTo(13.0 / 10_000.0, within(1e-9));
        assertThat(rare.precision()).isCloseTo(0.3, within(1e-12));
        assertThat(rare.lift())
                .as("flagged accounts are hundreds of times likelier to be anomalous")
                .isGreaterThan(200.0);
    }

    @Test
    @DisplayName("weighted counts add, and the honest sample size adds alongside")
    void weightedAddition() {
        ConfusionMatrix flagged = new ConfusionMatrix(6, 4, 0, 0, 10);
        ConfusionMatrix audit = new ConfusionMatrix(0, 0, 1900, 100, 10);

        ConfusionMatrix combined = flagged.plus(audit);

        assertThat(combined.truePositives()).isEqualTo(6);
        assertThat(combined.falseNegatives()).isEqualTo(100);
        assertThat(combined.labelled())
                .as("twenty accounts were actually looked at, whatever the weights say")
                .isEqualTo(20);
        assertThat(combined.recall()).isCloseTo(6.0 / 106.0, within(1e-12));
    }

    @Test
    @DisplayName("an empty matrix says nothing at all")
    void empty() {
        ConfusionMatrix empty = ConfusionMatrix.empty();

        assertThat(empty.precision()).isNaN();
        assertThat(empty.recall()).isNaN();
        assertThat(empty.baseRate()).isNaN();
        assertThat(empty.lift()).isNaN();
        assertThat(empty.labelled()).isZero();
    }
}
