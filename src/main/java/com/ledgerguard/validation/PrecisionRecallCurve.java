package com.ledgerguard.validation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Precision and recall at every threshold the data distinguishes.
 *
 * <h2>Why a curve rather than one number</h2>
 *
 * Phases 8 and 9 chose their elevation thresholds by judgement — 0.5 for the
 * composite, 0.6 for the isolation score — and said so. A single precision
 * figure at one of those thresholds cannot say whether the threshold was the
 * problem or the detector was. The curve separates the two: a detector that
 * ranks well but is cut in the wrong place has a good curve and a bad operating
 * point, and the fix is a number in a config file rather than a model.
 *
 * <h2>Why not ROC</h2>
 *
 * ROC-AUC is the conventional summary and it is misleading here. Under heavy
 * class imbalance — and fraud is nothing but class imbalance — the false
 * positive rate has a vast denominator, so thousands of false positives barely
 * move it and a mediocre detector posts an impressive-looking 0.95. Precision
 * carries the same false positives against the much smaller set of alerts, which
 * is the quantity anyone staffing a review queue actually feels.
 *
 * <h2>Average precision</h2>
 *
 * The curve is summarised by {@code sum over points of (recall_i −
 * recall_{i−1}) × precision_i}: the area under it, computed without the
 * interpolation that makes trapezoidal estimates optimistic at the sparse end.
 * Its floor is the base rate rather than 0.5, so a value of 0.30 means nothing
 * until read against a population that is 0.2% anomalous.
 */
public final class PrecisionRecallCurve {

    private PrecisionRecallCurve() {
    }

    /**
     * @param scored  the labelled population, weighted
     * @param scoreOf which score to sweep — the composite or the isolation score
     */
    public static Curve of(List<LabelledScore> scored, ToDoubleFunction<LabelledScore> scoreOf) {
        List<LabelledScore> ranked = scored.stream()
                .filter(row -> !Double.isNaN(scoreOf.applyAsDouble(row)))
                .sorted(Comparator.comparingDouble(scoreOf).reversed()
                        .thenComparing(LabelledScore::accountId))
                .toList();

        double totalPositives = ranked.stream()
                .filter(LabelledScore::isAnomalous)
                .mapToDouble(LabelledScore::weight)
                .sum();

        List<Point> points = new ArrayList<>();
        if (ranked.isEmpty() || totalPositives == 0) {
            return new Curve(points, Double.NaN, totalPositives, ranked.size());
        }

        double truePositives = 0;
        double flagged = 0;
        double previousRecall = 0;
        double averagePrecision = 0;

        for (int index = 0; index < ranked.size(); index++) {
            LabelledScore row = ranked.get(index);
            flagged += row.weight();
            if (row.isAnomalous()) {
                truePositives += row.weight();
            }

            // One point per distinct score. Emitting one mid-tie would report a
            // threshold nobody could actually set, since a cut inside a group of
            // equal scores cannot be expressed as a number.
            boolean lastOfTie = index == ranked.size() - 1
                    || scoreOf.applyAsDouble(ranked.get(index + 1)) != scoreOf.applyAsDouble(row);
            if (!lastOfTie) {
                continue;
            }

            double precision = truePositives / flagged;
            double recall = truePositives / totalPositives;
            points.add(new Point(scoreOf.applyAsDouble(row), precision, recall));

            averagePrecision += (recall - previousRecall) * precision;
            previousRecall = recall;
        }

        return new Curve(points, averagePrecision, totalPositives, ranked.size());
    }

    /**
     * @param threshold flagging at or above this score produces this point
     */
    public record Point(double threshold, double precision, double recall) {
    }

    /**
     * @param averagePrecision area under the curve. Compare against
     *                         {@code positives / labelled}, the score a detector
     *                         that ranked at random would get
     * @param labelled         accounts the curve rests on, unweighted — the only
     *                         honest measure of how much is behind it
     */
    public record Curve(List<Point> points, double averagePrecision,
                        double positives, int labelled) {

        public Curve {
            points = List.copyOf(points);
        }

        /** What random ranking would score: the base rate. The number to beat. */
        public double chanceLevel() {
            return labelled == 0 ? Double.NaN : positives / labelled;
        }

        /** The best F1 available at any threshold, and where it sits. */
        public java.util.Optional<Point> bestByF1() {
            return points.stream().max(Comparator.comparingDouble(point -> {
                double sum = point.precision() + point.recall();
                return sum == 0 ? 0 : 2 * point.precision() * point.recall() / sum;
            }));
        }
    }
}
