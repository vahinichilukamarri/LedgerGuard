package com.ledgerguard.detection.signals;

import com.ledgerguard.detection.DetectionSettings;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;
import com.ledgerguard.detection.stats.DiscreteTails;

/**
 * The shared machinery behind the two proportion signals.
 *
 * <p>Reconciliation mismatches and refund/reversals are different events with
 * the same statistical shape: {@code k} of {@code n} outcomes went a certain way
 * for this account, and the question is whether that is more often than the
 * ledger as a whole would predict. Both are therefore one binomial upper tail
 * against a smoothed population rate, and writing it once keeps the two signals
 * from drifting apart in their handling of the awkward cases.
 *
 * <h2>Why the population rate, not the account's own history</h2>
 *
 * Because per-account rates cannot be estimated. Most accounts have never had a
 * reconciliation mismatch, so their historical rate is exactly zero, and any
 * comparison against zero makes the first mismatch look infinitely improbable.
 * The ledger-wide rate is thin evidence about this particular account, and that
 * is a real limitation stated plainly rather than a modelling choice dressed up:
 * a genuinely per-account baseline needs either far more history or a
 * hierarchical model, and both belong in the ML phase.
 *
 * <h2>Why the smoothing is not optional</h2>
 *
 * A population that has never produced the event gives a rate of exactly zero,
 * and every observation of it becomes impossible rather than merely surprising.
 * Laplace smoothing says the honest thing: never having seen one is evidence
 * that they are rare, not proof that they cannot happen.
 */
final class RateSpike {

    private RateSpike() {
    }

    /**
     * @param observed         how many of this account's outcomes went the
     *                         interesting way, in the recent window
     * @param trials           how many outcomes there were to go that way
     * @param populationEvents the same count across the whole ledger
     * @param populationTrials the same denominator across the whole ledger
     * @param subject          what the outcomes are, for the explanation text
     */
    static SignalScore evaluate(Signal signal, DetectionSettings settings,
                                long observed, long trials,
                                long populationEvents, long populationTrials,
                                String subject) {

        if (trials < settings.minRateTrials()) {
            return SignalScore.insufficientData(signal,
                    ("%d %s in the window, need %d before a proportion means anything "
                            + "(one in three is 33 percent, and says nothing)")
                            .formatted(trials, subject, settings.minRateTrials()));
        }

        double populationRate = DiscreteTails.smoothedRate(populationEvents, populationTrials);
        double probability = DiscreteTails.binomialUpperTail((int) observed, (int) trials, populationRate);
        double surprisal = DiscreteTails.surprisal(probability);

        return SignalScore.of(signal, surprisal,
                ("%d of %d %s, a rate of %.1f against the ledger-wide %.1f percent "
                        + "(%d of %d, Laplace-smoothed); p = %.3g")
                        .formatted(observed, trials, subject,
                                100.0 * observed / trials, 100.0 * populationRate,
                                populationEvents, populationTrials, probability));
    }
}
