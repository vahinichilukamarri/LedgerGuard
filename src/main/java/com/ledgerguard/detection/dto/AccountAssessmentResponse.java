package com.ledgerguard.detection.dto;

import com.ledgerguard.detection.AnomalyScore;
import com.ledgerguard.detection.SignalScore;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.ModelMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * named by {@link Agreement} rather than resolved.
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

        List<AnomalyScoreResponse.SignalDetail> signals) {

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

    public static AccountAssessmentResponse of(AnomalyScore statistical,
                                               Optional<Double> mlScore,
                                               Optional<ModelMetadata> metadata,
                                               String unavailableReason) {

        MachineLearning ml = mlScore.isPresent() && metadata.isPresent()
                ? MachineLearning.of(mlScore.get(), statistical.composite(), metadata.get())
                : MachineLearning.unavailable(unavailableReason);

        return new AccountAssessmentResponse(
                statistical.accountId(),
                statistical.asOf(),
                statistical.composite(),
                statistical.applicableSignals(),
                statistical.isWellEvidenced(),
                ml,
                statistical.signals().stream()
                        .map(AccountAssessmentResponse::detail)
                        .toList());
    }

    private static AnomalyScoreResponse.SignalDetail detail(SignalScore score) {
        return new AnomalyScoreResponse.SignalDetail(
                score.signal().wireName(),
                score.applicable(),
                score.fired(),
                Double.isNaN(score.statistic()) ? null : score.statistic(),
                score.score(),
                score.signal().weight(),
                score.explanation(),
                score.subjectId());
    }
}
