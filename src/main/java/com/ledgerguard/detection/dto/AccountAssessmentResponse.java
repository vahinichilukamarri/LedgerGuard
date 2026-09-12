package com.ledgerguard.detection.dto;

import com.ledgerguard.detection.explain.AccountExplanation;
import com.ledgerguard.detection.explain.Reconciliation;
import com.ledgerguard.detection.explain.SignalContribution;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.ModelMetadata;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One account, assessed by both layers, with the two kept apart.
 *
 * <p>{@code statisticalScore} and {@code mlScore} are siblings with distinct
 * names and no blended field between them. That is the point: there is
 * deliberately no single number here for a caller to grab, because a single
 * number would imply a reconciliation of the two that nothing in this phase has
 * earned.
 *
 * <p>Neither score is validated against ground truth. Their relationship is
 * named by {@link Agreement} rather than resolved, and from Phase 10 it is also
 * described in words by {@link ExplanationDigest}.
 *
 * @param signals the per-signal breakdown, now carrying each signal's share of
 *                the composite as well as its raw numbers, and ordered by that
 *                share rather than by declaration order — the order a reviewer
 *                reads in
 */
public record AccountAssessmentResponse(
        UUID accountId,
        Instant asOf,

        /** Phase 8's weighted composite, in {@code [0,1]}. Explainable signal by signal. */
        double statisticalScore,
        int applicableSignals,
        boolean wellEvidenced,

        /** The model's view, kept separate. Absent until a model has been trained. */
        MachineLearning ml,

        List<SignalContribution> signals,
        ExplanationDigest explanation) {

    /**
     * @param available         false when no model has been trained. Distinct
     *                          from a score of zero, which would be a claim
     * @param score             the isolation score in {@code [0,1]}, where 0.5 is
     *                          the middle of the distribution rather than a
     *                          threshold
     * @param agreement         how the two layers relate; null when there is no
     *                          model to compare against
     * @param unavailableReason why there is no score, when there is not
     */
    public record MachineLearning(
            boolean available,
            Double score,
            String agreement,
            String unavailableReason,
            Model model) {

        static MachineLearning unavailable(String reason) {
            return new MachineLearning(false, null, null, reason, null);
        }

        static MachineLearning of(double score, double statisticalScore, ModelMetadata metadata) {
            return new MachineLearning(
                    true,
                    score,
                    Agreement.of(statisticalScore, score).name(),
                    null,
                    Model.from(metadata));
        }
    }

    /**
     * Which model produced the score.
     *
     * <p>Carried on every response rather than hidden behind a separate lookup,
     * because a forest score cannot be recomputed from the ledger by reading a
     * formula — the only way it stays reproducible is if the seed and the
     * training snapshot travel with it.
     */
    public record Model(long seed, int trees, int subSampleSize, int trainingAccounts,
                        Instant trainedAt, Instant trainedAsOf) {

        static Model from(ModelMetadata metadata) {
            return new Model(metadata.seed(), metadata.treeCount(), metadata.subSampleSize(),
                    metadata.trainingSampleSize(), metadata.trainedAt(), metadata.trainedAsOf());
        }
    }

    /**
     * The short form of the explanation, for callers reading a list.
     *
     * <p>The full attribution is four kilobytes an account and belongs at
     * {@link #detail}. What cannot be deferred to a second request is the
     * qualification: {@link #summary} is the generated prose in full, including
     * its closing sentence that neither score is validated, so no unqualified
     * finding leaves this endpoint even for a caller that never follows the
     * link. The per-account caveat list is at the detail endpoint.
     *
     * @param corroborated   whether the layers point at any shared axis. Worth
     *                       reading against {@code agreement}: false alongside
     *                       {@code BOTH_ELEVATED} means two elevated scores
     *                       resting on unrelated evidence
     * @param detail         where the full explanation lives
     */
    public record ExplanationDigest(
            String summary,
            String agreement,
            boolean corroborated,
            List<String> statisticalDrivers,
            List<String> modelDrivers,
            List<String> modelDriversOutsideStatisticalView,
            String detail) {

        static ExplanationDigest from(AccountExplanation explanation) {
            Reconciliation reconciliation = explanation.reconciliation();

            return new ExplanationDigest(
                    explanation.summary(),
                    reconciliation == null ? null : reconciliation.agreement().name(),
                    reconciliation != null && reconciliation.corroborated(),
                    explanation.statistical().drivers().stream()
                            .map(SignalContribution::signal)
                            .toList(),
                    reconciliation == null ? List.of() : reconciliation.modelDrivers(),
                    reconciliation == null ? List.of() : reconciliation.modelDriversOutsideView(),
                    "/detection/accounts/%s/explanation".formatted(explanation.accountId()));
        }
    }

    /**
     * Built from the explanation rather than from the scores directly, so the
     * numbers in the digest and the numbers beside it are the same numbers.
     */
    public static AccountAssessmentResponse of(AccountExplanation explanation, String unavailableReason) {
        MachineLearning ml = explanation.hasModel()
                ? MachineLearning.of(explanation.ml().score(),
                        explanation.statistical().composite(),
                        explanation.ml().model())
                : MachineLearning.unavailable(unavailableReason);

        return new AccountAssessmentResponse(
                explanation.accountId(),
                explanation.asOf(),
                explanation.statistical().composite(),
                explanation.statistical().applicableSignals(),
                explanation.statistical().wellEvidenced(),
                ml,
                explanation.statistical().contributions(),
                ExplanationDigest.from(explanation));
    }
}
