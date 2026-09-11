package com.ledgerguard.detection.signals;

import com.ledgerguard.detection.AccountActivity;
import com.ledgerguard.detection.AnomalySignal;
import com.ledgerguard.detection.DetectionSettings;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;
import com.ledgerguard.detection.stats.DiscreteTails;

import java.util.List;

/**
 * Is this account transacting faster than its own established rate?
 *
 * <h2>The statistic</h2>
 *
 * Treat the account's history as a Poisson process, estimate its rate from the
 * baseline period, and ask how improbable the recent window's count is under
 * that rate: {@code P(X ≥ k | λ·W)}, reported as surprisal.
 *
 * <p>The rate comes from the baseline <em>excluding</em> the recent window. That
 * exclusion is what makes the question meaningful — a burst included in its own
 * baseline raises the rate it is measured against, and a sufficiently large
 * spike would partly excuse itself.
 *
 * <h2>Why the exact tail rather than {@code (k − λ)/√λ}</h2>
 *
 * Because most accounts are quiet, and the normal approximation is worst when λ
 * is small. An account averaging 0.2 payments an hour that makes 3 scores 6.3 by
 * the approximation, which reads as extraordinary; the exact answer is
 * {@code p ≈ 0.0011}, surprisal 2.96 — just under the flagging line. The
 * approximation would have raised an alert the evidence does not support, and
 * would do so for every quiet account that had a mildly busy hour.
 */
public class VelocitySignal implements AnomalySignal {

    @Override
    public Signal signal() {
        return Signal.VELOCITY;
    }

    @Override
    public SignalScore evaluate(AccountActivity activity, DetectionSettings settings) {
        List<AccountActivity.PaymentEvent> baseline = activity.paymentsInBaseline();
        if (baseline.size() < settings.minBaselineEvents()) {
            return SignalScore.insufficientData(signal(),
                    "%d payments of history, need %d to establish a rate"
                            .formatted(baseline.size(), settings.minBaselineEvents()));
        }

        double baselineSeconds = activity.baselineSpan().toSeconds();
        double windowSeconds = activity.recentWindow().toSeconds();
        double ratePerSecond = baseline.size() / baselineSeconds;
        double expected = ratePerSecond * windowSeconds;

        int observed = activity.paymentsInRecentWindow().size();
        double probability = DiscreteTails.poissonUpperTail(observed, expected);
        double surprisal = DiscreteTails.surprisal(probability);

        return SignalScore.of(signal(), surprisal,
                ("%d payments in the last %s against an expected %.2f "
                        + "(rate of %.2f per hour from %d payments of history); p = %.3g")
                        .formatted(observed, humanise(activity.recentWindow()), expected,
                                ratePerSecond * 3600, baseline.size(), probability));
    }

    static String humanise(java.time.Duration duration) {
        long hours = duration.toHours();
        if (hours >= 24 && duration.toMinutesPart() == 0) {
            return duration.toDays() + "d";
        }
        if (hours >= 1 && duration.toMinutesPart() == 0) {
            return hours + "h";
        }
        long minutes = duration.toMinutes();
        return minutes >= 1 ? minutes + "m" : duration.toSeconds() + "s";
    }
}
