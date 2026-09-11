package com.ledgerguard.detection.signals;

import com.ledgerguard.config.Money;
import com.ledgerguard.detection.AccountActivity;
import com.ledgerguard.detection.AnomalySignal;
import com.ledgerguard.detection.DetectionSettings;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;
import com.ledgerguard.detection.stats.RobustStatistics;

import java.util.List;

/**
 * Is any recent payment far from what this account normally sends?
 *
 * <p>Judged against the account's own history rather than any global notion of a
 * large payment. A treasury account moving six figures hourly is unremarkable;
 * the same amount from an account that has only ever sent £20 is the entire
 * point of the signal. There is no absolute threshold anywhere in this class.
 *
 * <h2>The statistic</h2>
 *
 * The modified z-score of each payment in the recent window against the median
 * and MAD of the account's prior payments, and the largest of those is the
 * signal's answer. Largest rather than mean: one extreme payment among ten
 * ordinary ones is exactly the case worth surfacing, and averaging would dilute
 * it away.
 *
 * <p>Magnitude is used, not the signed value, so an unusually <em>small</em>
 * payment scores as highly as an unusually large one. Deliberate: a sequence of
 * trivial amounts against a normally-substantial account is what card testing
 * looks like, and a one-sided test would miss it entirely.
 *
 * <h2>Why it is capped rather than decayed</h2>
 *
 * The most recent {@link DetectionSettings#amountHistoryCap()} payments form the
 * baseline. Exponential decay would be the more sophisticated choice and is
 * deliberately not used: it needs a half-life, fitting one needs labelled data
 * this phase does not have, and an unfitted decay quietly reweights history by
 * an arbitrary constant while looking principled.
 */
public class AmountOutlierSignal implements AnomalySignal {

    @Override
    public Signal signal() {
        return Signal.AMOUNT_OUTLIER;
    }

    @Override
    public SignalScore evaluate(AccountActivity activity, DetectionSettings settings) {
        List<AccountActivity.PaymentEvent> recent = activity.paymentsInRecentWindow();
        if (recent.isEmpty()) {
            return SignalScore.insufficientData(signal(), "no payments in the recent window to judge");
        }

        List<AccountActivity.PaymentEvent> history = baseline(activity, settings);
        if (history.size() < settings.minAmountSamples()) {
            return SignalScore.insufficientData(signal(),
                    "%d prior payments, need %d before an amount distribution means anything"
                            .formatted(history.size(), settings.minAmountSamples()));
        }

        double[] amounts = history.stream()
                .mapToDouble(AccountActivity.PaymentEvent::amountMinor)
                .toArray();
        RobustStatistics.Dispersion dispersion = RobustStatistics.describe(amounts);

        AccountActivity.PaymentEvent worst = null;
        double worstZ = 0;
        for (AccountActivity.PaymentEvent payment : recent) {
            double z = RobustStatistics.modifiedZ(
                    payment.amountMinor(), dispersion, settings.degenerateCeiling());
            if (Math.abs(z) > Math.abs(worstZ)) {
                worstZ = z;
                worst = payment;
            }
        }

        if (worst == null) {
            // Every recent payment sits exactly on a degenerate median: the
            // account is doing precisely what it always does.
            return SignalScore.of(signal(), 0.0,
                    "every recent payment matches this account's usual %s exactly"
                            .formatted(format(dispersion.median(), activity.currency())));
        }

        return SignalScore.of(signal(), worstZ, explain(worstZ, worst, dispersion, activity),
                worst.paymentId());
    }

    /** The most recent {@code amountHistoryCap} payments before the window under judgement. */
    private static List<AccountActivity.PaymentEvent> baseline(AccountActivity activity,
                                                               DetectionSettings settings) {
        List<AccountActivity.PaymentEvent> prior = activity.paymentsInBaseline();
        if (prior.size() <= settings.amountHistoryCap()) {
            return prior;
        }
        return prior.subList(prior.size() - settings.amountHistoryCap(), prior.size());
    }

    private static String explain(double z, AccountActivity.PaymentEvent worst,
                                  RobustStatistics.Dispersion dispersion, AccountActivity activity) {
        String currency = activity.currency();
        String amount = format(worst.amountMinor(), currency);
        String median = format(dispersion.median(), currency);
        String direction = z > 0 ? "above" : "below";

        if (dispersion.isDegenerate()) {
            return ("%s differs from this account's only ever amount of %s; "
                    + "with no variation in %d prior payments the size of the departure cannot be measured")
                    .formatted(amount, median, dispersion.sampleSize());
        }

        String basis = switch (dispersion.basis()) {
            case MAD -> "median absolute deviation";
            case MEAN_ABSOLUTE_DEVIATION ->
                    "mean absolute deviation, because over half of the prior payments share one amount "
                            + "and the median absolute deviation collapsed to zero";
            case DEGENERATE -> "no dispersion";
        };

        return "%s is %.1f robust deviations %s this account's usual %s (%d prior payments, scale from %s)"
                .formatted(amount, Math.abs(z), direction, median, dispersion.sampleSize(), basis);
    }

    private static String format(double minorUnits, String currency) {
        return Money.toMajorUnits(Math.round(minorUnits), currency).toPlainString() + " " + currency;
    }
}
