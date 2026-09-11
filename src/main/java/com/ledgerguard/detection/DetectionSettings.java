package com.ledgerguard.detection;

import java.time.Duration;

/**
 * The windows and minimum sample sizes every signal shares.
 *
 * <h2>The minimums are the main false-positive control</h2>
 *
 * Not the thresholds. A threshold decides how extreme an observation must be; a
 * minimum decides whether there is enough evidence to have an opinion at all,
 * and thin evidence is where a detection layer embarrasses itself. Three
 * payments will always contain a largest one, and with a sample that small the
 * robust scale is nearly meaningless, so an account that has barely started
 * trading would be flagged for behaving like a new account.
 *
 * <p>Each minimum below is therefore chosen for what the statistic needs, not
 * for what feels cautious.
 *
 * @param recentWindow      what is being judged
 * @param baselineWindow    what it is judged against, ending where the recent
 *                          window begins
 * @param amountHistoryCap  most recent payments considered for the amount
 *                          distribution. A cap rather than a decay: an
 *                          exponential decay needs a half-life, and fitting one
 *                          needs data this phase does not have. An unfitted
 *                          decay silently reweights history by an arbitrary
 *                          constant, which is worse than a cap that is honest
 *                          about being a cap
 * @param minAmountSamples  below this, MAD is estimated from too few points to
 *                          mean anything
 * @param minBaselineEvents below this, the Poisson rate is not established
 *                          enough to call anything a departure from it
 * @param minRateTrials     below this, a proportion is dominated by its own
 *                          granularity: one mismatch out of three is 33%, and
 *                          says nothing
 * @param degenerateCeiling what a differing amount scores when an account's
 *                          history has no dispersion at all
 */
public record DetectionSettings(
        Duration recentWindow,
        Duration baselineWindow,
        int amountHistoryCap,
        int minAmountSamples,
        int minBaselineEvents,
        int minRateTrials,
        double degenerateCeiling) {

    public static DetectionSettings defaults() {
        return new DetectionSettings(
                Duration.ofHours(1),
                Duration.ofDays(30),
                200,
                8,
                20,
                10,
                Signal.AMOUNT_OUTLIER.saturation());
    }
}
