package com.ledgerguard.detection.dto;

import com.ledgerguard.detection.AnomalyScore;
import com.ledgerguard.detection.SignalScore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An account's score as it goes over the wire.
 *
 * <p>The per-signal breakdown is always included, never summarised away. A
 * caller that only wanted the number can ignore the rest; a caller that got only
 * the number could not reconstruct it, and with weights this phase has not
 * fitted, a score nobody can take apart is a score nobody should act on.
 */
public record AnomalyScoreResponse(
        UUID accountId,
        Instant asOf,
        double compositeScore,
        int applicableSignals,
        boolean wellEvidenced,
        List<SignalDetail> signals) {

    /**
     * @param statistic the raw statistic on its own scale, or null where the
     *                  signal had nothing to measure. Null rather than zero:
     *                  they mean different things and JSON has a way to say so
     */
    public record SignalDetail(
            String signal,
            boolean applicable,
            boolean fired,
            Double statistic,
            double score,
            double weight,
            String explanation,
            UUID subjectId) {

        static SignalDetail from(SignalScore score) {
            return new SignalDetail(
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

    public static AnomalyScoreResponse of(AnomalyScore score) {
        return new AnomalyScoreResponse(
                score.accountId(),
                score.asOf(),
                score.composite(),
                score.applicableSignals(),
                score.isWellEvidenced(),
                score.signals().stream().map(SignalDetail::from).toList());
    }
}
