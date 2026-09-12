package com.ledgerguard.validation.dto;

import com.ledgerguard.validation.AccountLabel;
import com.ledgerguard.validation.LabelSource;
import com.ledgerguard.validation.Stratum;
import com.ledgerguard.validation.Verdict;

import java.time.Instant;
import java.util.UUID;

/**
 * One label as it goes over the wire.
 *
 * <p>{@code latencySeconds} is published rather than left to be derived: the gap
 * between the behaviour and the truth about it is the property that makes these
 * labels hard to use, and a consumer who never computes it will evaluate as
 * though the two were simultaneous.
 */
public record LabelResponse(
        UUID id,
        UUID accountId,
        Verdict verdict,
        LabelSource source,
        Stratum stratum,
        String reviewer,
        boolean scoresVisible,
        Instant labelledAsOf,
        Instant observedAt,
        long latencySeconds,
        UUID evidenceId,
        String notes) {

    public static LabelResponse from(AccountLabel label) {
        return new LabelResponse(
                label.getId(), label.getAccountId(), label.getVerdict(), label.getSource(),
                label.getStratum(), label.getReviewer(), label.isScoresVisible(),
                label.getLabelledAsOf(), label.getObservedAt(), label.latency().toSeconds(),
                label.getEvidenceId(), label.getNotes());
    }
}
