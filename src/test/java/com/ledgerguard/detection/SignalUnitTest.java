package com.ledgerguard.detection;

import com.ledgerguard.detection.signals.AmountOutlierSignal;
import com.ledgerguard.detection.signals.BurstSignal;
import com.ledgerguard.detection.signals.ReconciliationMismatchSignal;
import com.ledgerguard.detection.signals.RefundReversalRateSignal;
import com.ledgerguard.detection.signals.VelocitySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each signal against synthetic histories: something obviously anomalous,
 * something clearly not, and the boundary in between.
 *
 * <p>No database and no clock. Signals are pure functions of an
 * {@link AccountActivity}, so a test states the history it wants and asserts the
 * answer, and two runs see byte-identical input. Nothing here needs seeding
 * because nothing here is random.
 */
class SignalUnitTest {

    private static final DetectionSettings SETTINGS = DetectionSettings.defaults();

    @Nested
    @DisplayName("S1 amount outlier")
    class AmountOutlier {

        private final AmountOutlierSignal signal = new AmountOutlierSignal();

        @Test
        @DisplayName("anomaly: a payment orders of magnitude above the account's usual")
        void obviousOutlier() {
            AccountActivity activity = ActivityFixtures.account()
                    .withVariedHistory(20, 10_000, 500)
                    .withRecentPayment(5_000_000, 10)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.fired()).isTrue();
            assertThat(score.statistic()).isGreaterThan(3.5);
            assertThat(score.score()).isEqualTo(1.0);
            assertThat(score.subjectId())
                    .as("a fired signal must name the payment, or nobody can investigate it")
                    .isNotNull();
        }

        @Test
        @DisplayName("non-anomaly: a payment squarely inside the usual spread")
        void ordinaryPayment() {
            AccountActivity activity = ActivityFixtures.account()
                    .withVariedHistory(20, 10_000, 500)
                    .withRecentPayment(10_200, 10)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.fired()).isFalse();
            assertThat(score.score()).isZero();
        }

        @Test
        @DisplayName("anomaly on the low side: card-testing amounts against a substantial account")
        void unusuallySmallPaymentAlsoFires() {
            AccountActivity activity = ActivityFixtures.account()
                    .withVariedHistory(20, 500_000, 10_000)
                    .withRecentPayment(50, 5)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.statistic()).isNegative();
            assertThat(score.fired())
                    .as("a one-sided test would miss card testing entirely")
                    .isTrue();
        }

        @Test
        @DisplayName("boundary: one payment short of the minimum sample is not judged at all")
        void justBelowMinimumSample() {
            AccountActivity activity = ActivityFixtures.account()
                    .withVariedHistory(SETTINGS.minAmountSamples() - 1, 10_000, 500)
                    .withRecentPayment(5_000_000, 10)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable())
                    .as("too little history is not a clean bill of health")
                    .isFalse();
            assertThat(score.score()).isZero();
            assertThat(score.explanation()).contains("insufficient data");
        }

        @Test
        @DisplayName("boundary: exactly the minimum sample is judged")
        void exactlyMinimumSample() {
            AccountActivity activity = ActivityFixtures.account()
                    .withVariedHistory(SETTINGS.minAmountSamples(), 10_000, 500)
                    .withRecentPayment(5_000_000, 10)
                    .build();

            assertThat(signal.evaluate(activity, SETTINGS).applicable()).isTrue();
        }

        /**
         * The degenerate case end to end. A subscription account pays the same
         * amount every month; the signal must not treat perfect regularity as
         * infinitely anomalous, and must not divide by zero getting there.
         */
        @Test
        @DisplayName("degenerate: an account that always pays the same amount, paying it again, scores zero")
        void constantHistoryPayingTheSameAmount() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(20, 2_500)
                    .withRecentPayment(2_500, 10)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.score())
                    .as("doing exactly what it always does is the opposite of anomalous")
                    .isZero();
            assertThat(score.statistic()).isNotNaN().isZero();
        }

        @Test
        @DisplayName("degenerate: the same account paying something different is bounded, not infinite")
        void constantHistoryPayingSomethingElse() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(20, 2_500)
                    .withRecentPayment(2_501, 10)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.statistic()).isFinite().isEqualTo(SETTINGS.degenerateCeiling());
            assertThat(score.score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("nothing in the window is nothing to judge")
        void noRecentPayments() {
            AccountActivity activity = ActivityFixtures.account()
                    .withVariedHistory(20, 10_000, 500)
                    .build();

            assertThat(signal.evaluate(activity, SETTINGS).applicable()).isFalse();
        }
    }

    @Nested
    @DisplayName("S2 velocity")
    class Velocity {

        private final VelocitySignal signal = new VelocitySignal();

        @Test
        @DisplayName("anomaly: ten payments in an hour from an account that makes one a day")
        void obviousSpike() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(30, 10_000)
                    .withRecentRun(10, 10_000, Duration.ofMinutes(4), Duration.ofMinutes(1))
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.statistic()).isGreaterThan(3.0);
            assertThat(score.score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("non-anomaly: a single payment in the window from the same account")
        void ordinaryActivity() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(30, 10_000)
                    .withRecentPayment(10_000, 10)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.fired()).isFalse();
        }

        @Test
        @DisplayName("boundary: one event short of an established rate is not judged")
        void justBelowMinimumBaseline() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(SETTINGS.minBaselineEvents() - 1, 10_000)
                    .withRecentRun(10, 10_000, Duration.ofMinutes(4), Duration.ofMinutes(1))
                    .build();

            assertThat(signal.evaluate(activity, SETTINGS).applicable())
                    .as("an account with no established rate cannot depart from it")
                    .isFalse();
        }

        @Test
        @DisplayName("boundary: exactly the minimum baseline is judged")
        void exactlyMinimumBaseline() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(SETTINGS.minBaselineEvents(), 10_000)
                    .withRecentRun(10, 10_000, Duration.ofMinutes(4), Duration.ofMinutes(1))
                    .build();

            assertThat(signal.evaluate(activity, SETTINGS).applicable()).isTrue();
        }
    }

    @Nested
    @DisplayName("S3 burst")
    class Burst {

        private final BurstSignal signal = new BurstSignal();

        @Test
        @DisplayName("anomaly: five payments inside two seconds")
        void obviousBurst() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(30, 10_000)
                    .withRecentRun(5, 10_000, Duration.ofMillis(500), Duration.ofMinutes(10))
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.statistic()).isGreaterThan(3.0);
            assertThat(score.score()).isEqualTo(1.0);
            assertThat(score.explanation()).contains("after correction");
        }

        @Test
        @DisplayName("non-anomaly: a busy account making three spread-out payments")
        void spreadActivityOnABusyAccount() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(3_000, 10_000)
                    .withRecentRun(3, 10_000, Duration.ofMinutes(20), Duration.ofMinutes(1))
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.fired())
                    .as("three payments across 40 minutes is ordinary for an account doing four an hour")
                    .isFalse();
        }

        /**
         * A pair is not a cluster. Every history has a shortest gap somewhere,
         * so scoring runs of two means firing on the tightest pair in any sample
         * at all — which the arithmetic confirmed before this rule was added.
         */
        @Test
        @DisplayName("boundary: two payments are a gap, not a cluster, and are not judged")
        void twoPaymentsAreNotACluster() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(30, 10_000)
                    .withRecentRun(2, 10_000, Duration.ofMillis(200), Duration.ofMinutes(10))
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isFalse();
            assertThat(score.explanation()).contains("cluster needs at least 3");
        }

        @Test
        @DisplayName("boundary: exactly three tightly packed payments do fire")
        void threeIsTheSmallestCluster() {
            AccountActivity activity = ActivityFixtures.account()
                    .withRegularHistory(30, 10_000)
                    .withRecentRun(3, 10_000, Duration.ofMillis(200), Duration.ofMinutes(10))
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.fired()).isTrue();
        }

        /**
         * The multiple-comparisons correction, asserted directly rather than
         * assumed. Scanning more windows must cost the signal something, or the
         * correction is not being applied.
         */
        @Test
        @DisplayName("the Bonferroni correction actually reduces the score as more windows are scanned")
        void correctionIsApplied() {
            AccountActivity fewWindows = ActivityFixtures.account()
                    .withRegularHistory(30, 10_000)
                    .withRecentRun(3, 10_000, Duration.ofMillis(100), Duration.ofMinutes(10))
                    .build();
            AccountActivity manyWindows = ActivityFixtures.account()
                    .withRegularHistory(30, 10_000)
                    .withRecentRun(3, 10_000, Duration.ofMillis(100), Duration.ofMinutes(10))
                    .withRecentRun(12, 10_000, Duration.ofMinutes(2), Duration.ofMinutes(20))
                    .build();

            String fewer = signal.evaluate(fewWindows, SETTINGS).explanation();
            String more = signal.evaluate(manyWindows, SETTINGS).explanation();

            assertThat(candidateWindows(fewer)).isLessThan(candidateWindows(more));
            assertThat(fewer).contains("after correction");
            assertThat(more).contains("after correction");
        }

        private static int candidateWindows(String explanation) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("across (\\d+) candidate windows").matcher(explanation);
            assertThat(matcher.find()).isTrue();
            return Integer.parseInt(matcher.group(1));
        }
    }

    @Nested
    @DisplayName("S4 reconciliation mismatch rate")
    class MismatchRate {

        private final ReconciliationMismatchSignal signal = new ReconciliationMismatchSignal();

        @Test
        @DisplayName("anomaly: three quarters of this account's transactions fail reconciliation")
        void obviousSpike() {
            AccountActivity activity = ActivityFixtures.account()
                    .withWindowTransactions(20, 15)
                    .withGlobalRates(10_000, 20, 10_000, 50)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.statistic()).isGreaterThan(3.0);
            assertThat(score.score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("non-anomaly: none of them do")
        void cleanAccount() {
            AccountActivity activity = ActivityFixtures.account()
                    .withWindowTransactions(20, 0)
                    .withGlobalRates(10_000, 20, 10_000, 50)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.fired()).isFalse();
        }

        @Test
        @DisplayName("boundary: one trial short of the minimum is not judged")
        void justBelowMinimumTrials() {
            AccountActivity activity = ActivityFixtures.account()
                    .withWindowTransactions(SETTINGS.minRateTrials() - 1, 9)
                    .withGlobalRates(10_000, 20, 10_000, 50)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable())
                    .as("nine out of nine is 100 percent and still says nothing")
                    .isFalse();
        }

        @Test
        @DisplayName("boundary: a population that has never had a mismatch does not make the first impossible")
        void unsmoothedPopulationWouldSaturate() {
            AccountActivity activity = ActivityFixtures.account()
                    .withWindowTransactions(20, 1)
                    .withGlobalRates(10_000, 0, 10_000, 0)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.statistic())
                    .as("without Laplace smoothing this would be infinite")
                    .isFinite();
        }
    }

    @Nested
    @DisplayName("S5 refund and reversal rate")
    class ReturnRate {

        private final RefundReversalRateSignal signal = new RefundReversalRateSignal();

        @Test
        @DisplayName("anomaly: most of this account's payments come straight back")
        void obviousSpike() {
            AccountActivity activity = ActivityFixtures.account()
                    .withWindowPayments(20, 16)
                    .withGlobalRates(10_000, 20, 10_000, 300)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("non-anomaly: a rate in line with the ledger as a whole")
        void ordinaryReturnRate() {
            AccountActivity activity = ActivityFixtures.account()
                    .withWindowPayments(20, 1)
                    .withGlobalRates(10_000, 20, 10_000, 300)
                    .build();

            SignalScore score = signal.evaluate(activity, SETTINGS);

            assertThat(score.applicable()).isTrue();
            assertThat(score.fired()).isFalse();
        }

        @Test
        @DisplayName("boundary: exactly the minimum number of trials is judged")
        void exactlyMinimumTrials() {
            AccountActivity activity = ActivityFixtures.account()
                    .withWindowPayments(SETTINGS.minRateTrials(), SETTINGS.minRateTrials())
                    .withGlobalRates(10_000, 20, 10_000, 300)
                    .build();

            assertThat(signal.evaluate(activity, SETTINGS).applicable()).isTrue();
        }
    }
}
