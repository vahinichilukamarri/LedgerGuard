package com.ledgerguard.validation;

import com.ledgerguard.detection.ml.Agreement;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the labels say about the detector, and what they cannot say.
 *
 * <h2>The warnings are not a footer</h2>
 *
 * {@link #warnings} is the most important field in this record, and it is
 * deliberately first among the things a reader should look at rather than a
 * caveat appended at the end. A report from forty labels with no audit stratum
 * and a model trained after the labelled period is not a slightly weaker report
 * than one from four thousand — it is a report that cannot support the sentences
 * someone will write from it. The figures are still published, because hiding
 * them would invite a worse guess; they are published next to the reasons they
 * may be wrong.
 *
 * <p>Every number here is an estimate of a property of <em>this</em> label set.
 * Whether that generalises depends on how the labels were sampled, which the
 * strata make measurable and do not make true.
 *
 * @param statistical  the Phase 8 composite, evaluated at its own threshold
 * @param model        the Phase 9 isolation score, when a forest exists
 * @param agreementStates precision by Phase 9's four-way state — the closest
 *                        thing this project has to an experiment on its own
 *                        central design decision
 */
public record EvaluationReport(
        Instant asOf,
        List<String> warnings,
        LabelCensus labels,
        ReviewQueue.Census population,
        LayerResult statistical,
        LayerResult model,
        Map<Agreement, StateResult> agreementStates,
        LabelService.Agreement reviewerAgreement) {

    public EvaluationReport {
        warnings = List.copyOf(warnings);
        agreementStates = Map.copyOf(agreementStates);
    }

    /**
     * @param decisive   labels that were ANOMALOUS or BENIGN; UNCLEAR ones are
     *                   counted and excluded, because forcing them into a
     *                   confusion matrix would invent agreement
     * @param auditLabels labels from the audit stratum. When this is zero,
     *                   recall is unmeasurable and the report says so rather
     *                   than printing a number
     * @param blindLabels labels a reviewer made without seeing the scores
     */
    public record LabelCensus(
            int total,
            int decisive,
            int unclear,
            int flaggedStratumLabels,
            int auditLabels,
            int disputeLabels,
            int syntheticLabels,
            int blindLabels) {
    }

    /**
     * One scoring layer's performance.
     *
     * @param threshold        the operating point these counts were taken at
     * @param precision        with its interval; the interval is what says
     *                         whether the point estimate means anything
     * @param recall           empty when no audit stratum exists, rather than a
     *                         number that would be precision in disguise
     * @param curve            the whole trade-off, so a bad threshold can be
     *                         told apart from a bad detector
     */
    public record LayerResult(
            double threshold,
            ConfusionMatrix confusion,
            Wilson.Interval precision,
            Wilson.Interval recall,
            boolean recallMeasurable,
            PrecisionRecallCurve.Curve curve) {
    }

    /**
     * @param anomalousShare of the labelled accounts in this agreement state,
     *                       the share labelled anomalous
     */
    public record StateResult(int labelled, double anomalousShare, Wilson.Interval interval) {
    }

    /** Whether anything here should be quoted without the warnings attached. */
    public boolean isQuotable() {
        return warnings.isEmpty();
    }
}
