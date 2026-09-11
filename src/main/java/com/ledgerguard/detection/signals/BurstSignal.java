package com.ledgerguard.detection.signals;

import com.ledgerguard.detection.AccountActivity;
import com.ledgerguard.detection.AnomalySignal;
import com.ledgerguard.detection.DetectionSettings;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;
import com.ledgerguard.detection.stats.DiscreteTails;

import java.time.Duration;
import java.util.List;

/**
 * Is the account's recent activity clustered far more tightly than its rate
 * explains?
 *
 * <h2>Why this is not the velocity signal again</h2>
 *
 * They answer different questions and can disagree in both directions. Ten
 * payments spread evenly across an hour and ten payments inside three seconds
 * have the <em>same hourly count</em>: velocity cannot tell them apart. Equally,
 * an account that normally makes two payments an hour making three of them in
 * the same second has not raised its rate at all, and velocity would stay quiet
 * while something clearly worth looking at happened.
 *
 * <p>Velocity asks how <em>many</em>. This asks how <em>tightly packed</em>.
 *
 * <h2>The scan, and why it must be corrected</h2>
 *
 * The statistic is a scan: over every contiguous run of {@code k} payments in
 * the recent window, take the shortest span containing them and compute
 * {@code P(X ≥ k | λ·w)} under the account's baseline rate. The most improbable
 * such window is the candidate burst.
 *
 * <p><b>Searching for the most improbable window is a multiple comparison, and
 * ignoring that would make this signal worthless.</b> Any Poisson process
 * contains runs that look tight in isolation; scan a few hundred candidate
 * windows and one of them will always clear a fixed threshold. An uncorrected
 * scan statistic is not a burst detector, it is a random number generator with
 * an alarming name.
 *
 * <p>So the smallest probability found is Bonferroni-corrected by the number of
 * windows examined. Bonferroni is conservative here — the windows overlap
 * heavily and are nothing like independent — and that is the right direction to
 * err in. A burst detector that cries wolf is switched off within a week, at
 * which point its sensitivity stops mattering.
 */
public class BurstSignal implements AnomalySignal {

    /**
     * Timestamps have finite resolution, so two genuinely separate payments can
     * share one. A zero-width window would make the expected count zero and the
     * surprisal unbounded, so spans are floored at one microsecond — the
     * resolution PostgreSQL stores.
     */
    private static final double MINIMUM_SPAN_SECONDS = 1e-6;

    @Override
    public Signal signal() {
        return Signal.BURST;
    }

    @Override
    public SignalScore evaluate(AccountActivity activity, DetectionSettings settings) {
        List<AccountActivity.PaymentEvent> baseline = activity.paymentsInBaseline();
        if (baseline.size() < settings.minBaselineEvents()) {
            return SignalScore.insufficientData(signal(),
                    "%d payments of history, need %d to establish a rate to cluster against"
                            .formatted(baseline.size(), settings.minBaselineEvents()));
        }

        List<AccountActivity.PaymentEvent> recent = activity.paymentsInRecentWindow();
        if (recent.size() < 2) {
            return SignalScore.insufficientData(signal(),
                    "%d payments in the recent window; clustering needs at least two"
                            .formatted(recent.size()));
        }

        double ratePerSecond = baseline.size() / (double) activity.baselineSpan().toSeconds();

        double tightestProbability = 1.0;
        int windowsExamined = 0;
        int burstSize = 0;
        double burstSpanSeconds = 0;

        // Every contiguous run of k payments, for every k. Contiguous in time
        // order is sufficient: the tightest window containing k events always
        // has an event at each end and none of the others in between.
        for (int k = 2; k <= recent.size(); k++) {
            for (int start = 0; start + k <= recent.size(); start++) {
                double spanSeconds = Math.max(MINIMUM_SPAN_SECONDS,
                        seconds(recent.get(start).occurredAt(), recent.get(start + k - 1).occurredAt()));
                double expected = ratePerSecond * spanSeconds;
                double probability = DiscreteTails.poissonUpperTail(k, expected);

                windowsExamined++;
                if (probability < tightestProbability) {
                    tightestProbability = probability;
                    burstSize = k;
                    burstSpanSeconds = spanSeconds;
                }
            }
        }

        double corrected = Math.min(1.0, tightestProbability * windowsExamined);
        double surprisal = DiscreteTails.surprisal(corrected);

        return SignalScore.of(signal(), surprisal,
                ("%d payments within %s, against a baseline rate of %.2f per hour; "
                        + "p = %.3g across %d candidate windows, %.3g after correction")
                        .formatted(burstSize, humaniseSeconds(burstSpanSeconds), ratePerSecond * 3600,
                                tightestProbability, windowsExamined, corrected));
    }

    private static double seconds(java.time.Instant from, java.time.Instant to) {
        Duration between = Duration.between(from, to);
        return between.getSeconds() + between.getNano() / 1_000_000_000.0;
    }

    private static String humaniseSeconds(double seconds) {
        if (seconds < 1) {
            return "%.0f ms".formatted(seconds * 1000);
        }
        if (seconds < 60) {
            return "%.1f s".formatted(seconds);
        }
        return "%.1f min".formatted(seconds / 60);
    }
}
