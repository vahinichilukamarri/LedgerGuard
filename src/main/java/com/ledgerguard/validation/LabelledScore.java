package com.ledgerguard.validation;

import java.time.Instant;
import java.util.UUID;

/**
 * One account, the scores it had <em>at the moment the label describes</em>, and
 * the verdict.
 *
 * <h2>The timestamp is the whole point</h2>
 *
 * {@link #asOf} is {@code labelledAsOf} from the label, not now. A chargeback
 * raised in November is evidence about behaviour in September, and scoring the
 * account with everything that happened in between would hand the detector
 * sixty days of consequences — refunds, reversals, the reconciliation incidents
 * the fraud itself caused — that nobody had on the day it would have had to make
 * the call.
 *
 * <p>That is temporal leakage, and it does not produce a slightly optimistic
 * number. It produces an excellent one, which is why it is so often missed: the
 * evaluation looks like a triumph and the detector fails in production, because
 * in production the future has not happened yet.
 *
 * @param weight how many accounts in the population this one stands for. One for
 *               a census, larger for a sampled stratum; see {@link Stratum}
 * @param mlScore null when no model could score this account at this instant
 */
public record LabelledScore(
        UUID accountId,
        Instant asOf,
        double statisticalScore,
        Double mlScore,
        Verdict verdict,
        LabelSource source,
        Stratum stratum,
        boolean scoresVisible,
        double weight) {

    public boolean isAnomalous() {
        return verdict == Verdict.ANOMALOUS;
    }

    public boolean hasModelScore() {
        return mlScore != null;
    }
}
