package com.ledgerguard.validation.dto;

import com.ledgerguard.validation.ReviewQueue;
import com.ledgerguard.validation.Stratum;

import java.util.UUID;

/**
 * The next account to judge.
 *
 * <h2>Blind by default</h2>
 *
 * The scores are null unless the caller explicitly asks to see them. A reviewer
 * shown "0.87" before deciding is producing an opinion about the detector's
 * opinion, and a detector evaluated against anchored labels is largely measuring
 * its own influence — so the safe mode is the default and seeing the scores is
 * the thing you have to ask for.
 *
 * <p>{@code paymentsInBaseline} is offered in both modes because a reviewer
 * needs somewhere to start, and a count of payments says nothing about how
 * interesting the detector found them.
 */
public record ReviewCandidateResponse(
        UUID accountId,
        Stratum stratum,
        boolean blind,
        Double statisticalScore,
        Double mlScore,
        int paymentsInBaseline) {

    public static ReviewCandidateResponse of(ReviewQueue.Candidate candidate, boolean blind) {
        return new ReviewCandidateResponse(
                candidate.accountId(),
                candidate.stratum(),
                blind,
                blind ? null : candidate.statisticalScore(),
                blind ? null : candidate.mlScore(),
                candidate.paymentsInBaseline());
    }
}
