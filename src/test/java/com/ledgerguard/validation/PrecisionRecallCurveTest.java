package com.ledgerguard.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The trade-off curve, and the summary that has to be read against chance.
 *
 * <p>The two anchoring cases are a detector that ranks perfectly and one that
 * ranks at random. Average precision is 1.0 for the first and approximately the
 * base rate for the second, which is what makes the number interpretable at all:
 * on a population that is 2% anomalous, an average precision of 0.10 is five
 * times chance and would look like failure to anyone reading it as a percentage.
 */
class PrecisionRecallCurveTest {

    private static final Instant AS_OF = Instant.parse("2026-09-12T10:00:00Z");

    private static LabelledScore row(double score, boolean anomalous) {
        return new LabelledScore(UUID.randomUUID(), AS_OF, score, score,
                anomalous ? Verdict.ANOMALOUS : Verdict.BENIGN,
                LabelSource.HUMAN_REVIEW, Stratum.FLAGGED, false, 1.0);
    }

    @Test
    @DisplayName("a perfect ranking scores 1.0")
    void perfectRanking() {
        List<LabelledScore> scored = List.of(
                row(0.9, true), row(0.8, true), row(0.7, true),
                row(0.3, false), row(0.2, false), row(0.1, false));

        PrecisionRecallCurve.Curve curve =
                PrecisionRecallCurve.of(scored, LabelledScore::statisticalScore);

        assertThat(curve.averagePrecision()).isCloseTo(1.0, within(1e-12));
        assertThat(curve.chanceLevel()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("an inverted ranking scores badly")
    void invertedRanking() {
        List<LabelledScore> scored = List.of(
                row(0.9, false), row(0.8, false), row(0.7, false),
                row(0.3, true), row(0.2, true), row(0.1, true));

        PrecisionRecallCurve.Curve curve =
                PrecisionRecallCurve.of(scored, LabelledScore::statisticalScore);

        assertThat(curve.averagePrecision())
                .as("worse than chance, which is what a detector ranking backwards deserves")
                .isLessThan(curve.chanceLevel());
    }

    @Test
    @DisplayName("random ranking lands near the base rate, which is what chance means here")
    void randomRankingLandsNearChance() {
        java.util.Random random = new java.util.Random(20260912L);
        List<LabelledScore> scored = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            scored.add(row(random.nextDouble(), i % 10 == 0));
        }

        PrecisionRecallCurve.Curve curve =
                PrecisionRecallCurve.of(scored, LabelledScore::statisticalScore);

        assertThat(curve.chanceLevel()).isCloseTo(0.1, within(1e-9));
        assertThat(curve.averagePrecision())
                .as("a score uncorrelated with the label cannot beat the base rate by much")
                .isCloseTo(0.1, within(0.06));
    }

    /**
     * A cut inside a group of equal scores is not a threshold anyone can set,
     * so the curve must not report one.
     */
    @Test
    @DisplayName("tied scores produce one point, not one per row")
    void tiesCollapseToOnePoint() {
        List<LabelledScore> scored = List.of(
                row(0.5, true), row(0.5, false), row(0.5, true), row(0.1, false));

        PrecisionRecallCurve.Curve curve =
                PrecisionRecallCurve.of(scored, LabelledScore::statisticalScore);

        assertThat(curve.points()).hasSize(2);
        assertThat(curve.points().get(0).threshold()).isEqualTo(0.5);
        assertThat(curve.points().get(0).precision())
                .as("flagging at 0.5 takes all three tied accounts, two of which are anomalous")
                .isCloseTo(2.0 / 3.0, within(1e-12));
    }

    @Test
    @DisplayName("weights carry into the curve")
    void weighted() {
        LabelledScore heavyBenign = new LabelledScore(UUID.randomUUID(), AS_OF, 0.9, 0.9,
                Verdict.BENIGN, LabelSource.HUMAN_REVIEW, Stratum.AUDIT, false, 100.0);

        PrecisionRecallCurve.Curve curve = PrecisionRecallCurve.of(
                List.of(heavyBenign, row(0.8, true)), LabelledScore::statisticalScore);

        assertThat(curve.points().get(0).precision())
                .as("one audit account standing for a hundred drowns a single true positive")
                .isLessThan(0.02);
    }

    @Test
    @DisplayName("with no positives there is no curve to draw")
    void noPositives() {
        PrecisionRecallCurve.Curve curve = PrecisionRecallCurve.of(
                List.of(row(0.9, false), row(0.1, false)), LabelledScore::statisticalScore);

        assertThat(curve.points()).isEmpty();
        assertThat(curve.averagePrecision()).isNaN();
    }

    @Test
    @DisplayName("an empty population produces an empty curve rather than an exception")
    void empty() {
        PrecisionRecallCurve.Curve curve =
                PrecisionRecallCurve.of(List.of(), LabelledScore::statisticalScore);

        assertThat(curve.points()).isEmpty();
        assertThat(curve.averagePrecision()).isNaN();
        assertThat(curve.chanceLevel()).isNaN();
    }

    @Test
    @DisplayName("the best operating point is reported, so a bad threshold is visible")
    void bestByF1() {
        List<LabelledScore> scored = List.of(
                row(0.95, true), row(0.90, true), row(0.60, true),
                row(0.55, false), row(0.20, false));

        PrecisionRecallCurve.Curve curve =
                PrecisionRecallCurve.of(scored, LabelledScore::statisticalScore);

        assertThat(curve.bestByF1()).isPresent();
        assertThat(curve.bestByF1().orElseThrow().threshold())
                .as("cutting at 0.60 catches every anomaly with no false positives")
                .isEqualTo(0.60);
    }

    @Test
    @DisplayName("rows with no score for this layer are skipped rather than counted as zero")
    void missingScoresAreSkipped() {
        LabelledScore noModel = new LabelledScore(UUID.randomUUID(), AS_OF, 0.9, null,
                Verdict.ANOMALOUS, LabelSource.HUMAN_REVIEW, Stratum.FLAGGED, false, 1.0);

        PrecisionRecallCurve.Curve curve = PrecisionRecallCurve.of(
                List.of(noModel, row(0.8, true), row(0.1, false)),
                row -> row.mlScore() == null ? Double.NaN : row.mlScore());

        assertThat(curve.labelled())
                .as("an account the model never scored is not an account the model got wrong")
                .isEqualTo(2);
    }
}
