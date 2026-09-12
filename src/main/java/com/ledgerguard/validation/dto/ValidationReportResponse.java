package com.ledgerguard.validation.dto;

import com.ledgerguard.validation.ConfusionMatrix;
import com.ledgerguard.validation.EvaluationReport;
import com.ledgerguard.validation.PrecisionRecallCurve;
import com.ledgerguard.validation.Wilson;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The evaluation, on the wire.
 *
 * <h2>NaN becomes null, deliberately</h2>
 *
 * An unmeasured proportion is {@code NaN} in the arithmetic and {@code null}
 * here. Jackson would otherwise emit the string {@code "NaN"}, which a consumer
 * parses as a number, coerces to zero, and charts as a precision of 0% — a
 * detector that measured nothing rendered as a detector that got everything
 * wrong. Null is the only encoding that survives that journey.
 *
 * <h2>Warnings first</h2>
 *
 * The field order is not cosmetic. Anything reading this top-down meets the
 * reasons the numbers may be wrong before it meets the numbers.
 */
public record ValidationReportResponse(
        Instant asOf,
        List<String> warnings,
        boolean quotable,
        EvaluationReport.LabelCensus labels,
        Population population,
        ReviewerAgreement reviewerAgreement,
        Layer statistical,
        Layer model,
        Map<String, State> agreementStates) {

    /** @param sampledFraction how much of each pool has actually been looked at */
    public record Population(int flagged, int unflagged, int total) {

        static Population from(com.ledgerguard.validation.ReviewQueue.Census census) {
            return new Population(census.flagged(), census.unflagged(), census.total());
        }
    }

    /**
     * @param rate null when nobody has been re-reviewed. Null rather than 1.0,
     *             because unmeasured agreement is not perfect agreement, and this
     *             number is the ceiling on every other one in the payload
     */
    public record ReviewerAgreement(int doublyReviewed, int agreed, Double rate) {

        static ReviewerAgreement from(com.ledgerguard.validation.LabelService.Agreement agreement) {
            return new ReviewerAgreement(agreement.doublyReviewed(), agreement.agreed(),
                    agreement.rate().orElse(null));
        }
    }

    public record Interval(Double point, double low, double high, long trials, boolean informative) {

        static Interval from(Wilson.Interval interval) {
            return new Interval(nullIfNaN(interval.point()), interval.low(), interval.high(),
                    interval.trials(), interval.isInformative());
        }
    }

    /**
     * @param recall null when no audit stratum exists. The report refuses to
     *               print a recall that would be 1.0 by construction
     * @param baseRate the share of the population that is anomalous, without
     *                 which precision cannot be read at all
     */
    public record Layer(
            double threshold,
            Counts counts,
            Interval precision,
            Interval recall,
            boolean recallMeasurable,
            Double f1,
            Double baseRate,
            Double lift,
            Double averagePrecision,
            Double chanceLevel,
            List<CurvePoint> curve) {

        static Layer from(EvaluationReport.LayerResult result) {
            ConfusionMatrix confusion = result.confusion();
            PrecisionRecallCurve.Curve curve = result.curve();

            return new Layer(
                    result.threshold(),
                    Counts.from(confusion),
                    Interval.from(result.precision()),
                    result.recallMeasurable() ? Interval.from(result.recall()) : null,
                    result.recallMeasurable(),
                    nullIfNaN(confusion.f1()),
                    nullIfNaN(confusion.baseRate()),
                    nullIfNaN(confusion.lift()),
                    nullIfNaN(curve.averagePrecision()),
                    nullIfNaN(curve.chanceLevel()),
                    curve.points().stream().map(CurvePoint::from).toList());
        }
    }

    /** @param labelled how many real accounts these weighted counts rest on */
    public record Counts(double truePositives, double falsePositives,
                         double trueNegatives, double falseNegatives, long labelled) {

        static Counts from(ConfusionMatrix confusion) {
            return new Counts(confusion.truePositives(), confusion.falsePositives(),
                    confusion.trueNegatives(), confusion.falseNegatives(), confusion.labelled());
        }
    }

    public record CurvePoint(double threshold, double precision, double recall) {

        static CurvePoint from(PrecisionRecallCurve.Point point) {
            return new CurvePoint(point.threshold(), point.precision(), point.recall());
        }
    }

    public record State(int labelled, Double anomalousShare, Interval interval) {

        static State from(EvaluationReport.StateResult result) {
            return new State(result.labelled(), nullIfNaN(result.anomalousShare()),
                    Interval.from(result.interval()));
        }
    }

    public static ValidationReportResponse from(EvaluationReport report) {
        Map<String, State> states = report.agreementStates().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().name(),
                        entry -> State.from(entry.getValue())));

        return new ValidationReportResponse(
                report.asOf(),
                report.warnings(),
                report.isQuotable(),
                report.labels(),
                Population.from(report.population()),
                ReviewerAgreement.from(report.reviewerAgreement()),
                Layer.from(report.statistical()),
                Layer.from(report.model()),
                states);
    }

    private static Double nullIfNaN(double value) {
        return Double.isNaN(value) ? null : value;
    }
}
