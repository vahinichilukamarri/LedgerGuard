package com.ledgerguard.detection.ml;

import com.ledgerguard.detection.AccountActivity;
import com.ledgerguard.detection.AnomalyScore;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;

import java.time.Duration;
import java.util.List;

/**
 * Turns one account's activity and statistical assessment into a feature vector.
 *
 * <p>A pure function, like the signals themselves: no repository, no clock, no
 * state. Training and scoring therefore run identical code over identical
 * inputs, which is what makes the determinism tests meaningful — a feature
 * pipeline that behaved differently at training time would produce a model that
 * scores its own training data incorrectly, and nothing here would notice.
 */
public final class FeatureExtractor {

    /**
     * What is reported when an account has never paid at all, so there is no
     * "time since" to measure. The baseline window is the longest gap the
     * detection layer can observe, so it stands for maximally dormant; anything
     * larger would be inventing a measurement out of an absence.
     */
    private static final double NO_PAYMENTS_PERCENTILE = 0.5;

    private FeatureExtractor() {
    }

    public static FeatureVector extract(AccountActivity activity, AnomalyScore score) {
        List<AccountActivity.PaymentEvent> recent = activity.paymentsInRecentWindow();
        List<AccountActivity.PaymentEvent> baseline = activity.paymentsInBaseline();

        long largestRecent = recent.stream()
                .mapToLong(AccountActivity.PaymentEvent::amountMinor)
                .max()
                .orElse(0L);

        return new FeatureVector(
                statistic(score, Signal.AMOUNT_OUTLIER),
                statistic(score, Signal.VELOCITY),
                statistic(score, Signal.BURST),
                statistic(score, Signal.RECONCILIATION_MISMATCH_RATE),
                statistic(score, Signal.REFUND_REVERSAL_RATE),
                log10(largestRecent),
                recent.size(),
                log10(secondsSinceLastPayment(activity)),
                percentileOf(largestRecent, baseline),
                returnedFraction(activity),
                score.applicableSignals() / (double) Signal.values().length);
    }

    /**
     * A signal's raw statistic, with {@code NaN} imputed as zero.
     *
     * <p>Zero is the value a perfectly unremarkable account produces, so the
     * imputation reads as "nothing to see" rather than as a distinct sentinel
     * the forest would isolate on. The cost is that it is indistinguishable from
     * a genuine measurement of nothing, which is what {@code dataCompleteness}
     * is for.
     */
    private static double statistic(AnomalyScore score, Signal signal) {
        return score.signals().stream()
                .filter(candidate -> candidate.signal() == signal)
                .map(SignalScore::statistic)
                .filter(value -> !Double.isNaN(value))
                .findFirst()
                .orElse(0.0);
    }

    /**
     * Seconds between the account's most recent payment and the instant being
     * assessed; the full baseline window when it has never paid.
     */
    private static double secondsSinceLastPayment(AccountActivity activity) {
        List<AccountActivity.PaymentEvent> payments = activity.outboundPayments();
        if (payments.isEmpty()) {
            return activity.baselineWindow().toSeconds();
        }
        AccountActivity.PaymentEvent last = payments.get(payments.size() - 1);
        long seconds = Duration.between(last.occurredAt(), activity.asOf()).toSeconds();
        return Math.max(0, seconds);
    }

    /**
     * The fraction of historical payments at or below {@code amountMinor}.
     *
     * <p>A plain rank rather than an interpolated quantile: with the small
     * per-account histories this layer works from, interpolation would imply a
     * precision the sample does not support.
     */
    private static double percentileOf(long amountMinor, List<AccountActivity.PaymentEvent> baseline) {
        if (baseline.isEmpty()) {
            return NO_PAYMENTS_PERCENTILE;
        }
        long atOrBelow = baseline.stream()
                .filter(payment -> payment.amountMinor() <= amountMinor)
                .count();
        return atOrBelow / (double) baseline.size();
    }

    private static double returnedFraction(AccountActivity activity) {
        if (activity.paymentsInWindow() <= 0) {
            return 0.0;
        }
        return activity.refundedOrReversedPaymentsInWindow() / (double) activity.paymentsInWindow();
    }

    /**
     * {@code log10(1 + x)}, so zero maps to zero and the transform is defined at
     * the bottom of the range. Amounts and elapsed times both span many orders
     * of magnitude, and a forest splitting uniformly across a raw range of that
     * width would put nearly every split in the sparse tail.
     */
    private static double log10(double value) {
        return Math.log10(1.0 + Math.max(0.0, value));
    }
}
