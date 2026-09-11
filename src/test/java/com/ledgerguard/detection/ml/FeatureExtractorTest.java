package com.ledgerguard.detection.ml;

import com.ledgerguard.detection.AccountActivity;
import com.ledgerguard.detection.AnomalyScore;
import com.ledgerguard.detection.AnomalyScorer;
import com.ledgerguard.detection.DetectionSettings;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The feature pipeline, which has to be right before anything the model says
 * means anything.
 *
 * <p>A wrong feature produces a model that is perfectly self-consistent and
 * describes something other than what anyone intended, and no test of the forest
 * itself would catch it. These are deliberately concrete: known inputs, arithmetic
 * done by hand.
 */
class FeatureExtractorTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    private static final DetectionSettings SETTINGS = DetectionSettings.defaults();

    @Test
    @DisplayName("the vector length and the name list cannot drift apart")
    void dimensionMatchesNames() {
        FeatureVector vector = extract(activity(List.of(), 0, 0, 0, 0));

        assertThat(FeatureVector.NAMES).hasSize(FeatureVector.dimension());
        assertThat(vector.toArray())
                .as("the forest addresses features by index, so this is load-bearing")
                .hasSize(FeatureVector.NAMES.length);
    }

    @Test
    @DisplayName("an unmeasurable signal is imputed as zero and recorded in dataCompleteness")
    void imputationIsVisible() {
        // No history at all, so every signal declines to judge.
        FeatureVector vector = extract(activity(List.of(), 0, 0, 0, 0));

        assertThat(vector.amountModifiedZ()).isZero();
        assertThat(vector.velocitySurprisal()).isZero();
        assertThat(vector.burstSurprisal()).isZero();
        assertThat(vector.mismatchSurprisal()).isZero();
        assertThat(vector.returnSurprisal()).isZero();
        assertThat(vector.dataCompleteness())
                .as("zeros from imputation must be distinguishable from zeros that were measured")
                .isZero();

        for (double value : vector.toArray()) {
            assertThat(value).as("a NaN here would propagate through every split").isNotNaN();
        }
    }

    @Test
    @DisplayName("dataCompleteness rises as signals become measurable")
    void completenessTracksApplicability() {
        List<AccountActivity.PaymentEvent> history = dailyPayments(25, 10_000);

        // One recent payment leaves burst unmeasurable, since a cluster needs
        // three. That is the fraction the model is meant to be able to see.
        history.add(payment(10_050, NOW.minusSeconds(600)));
        assertThat(extract(activity(history, 20, 0, 20, 0)).dataCompleteness())
                .as("four of five: burst has nothing to cluster")
                .isCloseTo(0.8, within(1e-9));

        history.add(payment(10_060, NOW.minusSeconds(500)));
        history.add(payment(10_070, NOW.minusSeconds(400)));

        assertThat(extract(activity(history, 20, 0, 20, 0)).dataCompleteness())
                .as("with a cluster to measure, all five signals have something to say")
                .isEqualTo(1.0);
    }

    /**
     * Phase 8 takes the magnitude of the amount z-score because it needs one
     * number for a human. The model keeps the sign, because a payment far below
     * an account's usual is a different behaviour from one far above.
     */
    @Test
    @DisplayName("the amount z-score keeps its sign, unlike Phase 8's own score")
    void amountZIsSigned() {
        List<AccountActivity.PaymentEvent> history = variedHistory(20, 500_000, 10_000);
        history.add(payment(50, NOW.minusSeconds(300)));

        FeatureVector vector = extract(activity(history, 0, 0, 0, 0));

        assertThat(vector.amountModifiedZ())
                .as("an unusually small payment must be distinguishable from an unusually large one")
                .isNegative();
    }

    @Test
    @DisplayName("amounts and elapsed times are log-transformed, so magnitudes do not swamp the splits")
    void logTransforms() {
        List<AccountActivity.PaymentEvent> history = dailyPayments(10, 1_000);
        history.add(payment(999_999, NOW.minus(Duration.ofMinutes(10))));

        FeatureVector vector = extract(activity(history, 0, 0, 0, 0));

        assertThat(vector.log10LargestRecentAmount())
                .isCloseTo(Math.log10(1_000_000.0), within(1e-9));
        assertThat(vector.log10SecondsSinceLastPayment())
                .isCloseTo(Math.log10(601.0), within(1e-9));
    }

    @Test
    @DisplayName("an account that has never paid reads as maximally dormant rather than as zero")
    void noPaymentsAtAll() {
        FeatureVector vector = extract(activity(List.of(), 0, 0, 0, 0));

        assertThat(vector.log10SecondsSinceLastPayment())
                .as("zero would mean 'paid just now', which is the opposite of the truth")
                .isCloseTo(Math.log10(1.0 + SETTINGS.baselineWindow().toSeconds()), within(1e-9));
        assertThat(vector.historicalAmountPercentile())
                .as("with no history there is no rank; the neutral middle is the honest answer")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("the historical percentile is the fraction of past payments at or below")
    void percentile() {
        List<AccountActivity.PaymentEvent> history = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            history.add(payment(i * 100, NOW.minus(Duration.ofDays(11 - i))));
        }
        // A recent payment larger than six of the ten.
        history.add(payment(650, NOW.minus(Duration.ofMinutes(5))));

        FeatureVector vector = extract(activity(history, 0, 0, 0, 0));

        assertThat(vector.historicalAmountPercentile()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    @DisplayName("the largest recent payment is the one described, not the latest")
    void largestNotLatest() {
        List<AccountActivity.PaymentEvent> history = dailyPayments(10, 1_000);
        history.add(payment(80_000, NOW.minus(Duration.ofMinutes(30))));
        history.add(payment(1_000, NOW.minus(Duration.ofMinutes(2))));

        FeatureVector vector = extract(activity(history, 0, 0, 0, 0));

        assertThat(vector.log10LargestRecentAmount())
                .isCloseTo(Math.log10(80_001.0), within(1e-9));
        assertThat(vector.recentPaymentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the returned fraction is this account's own rate, not the population's")
    void returnedFraction() {
        FeatureVector withReturns = extract(activity(List.of(), 0, 0, 20, 5));
        FeatureVector without = extract(activity(List.of(), 0, 0, 20, 0));

        assertThat(withReturns.returnedPaymentFraction()).isCloseTo(0.25, within(1e-9));
        assertThat(without.returnedPaymentFraction()).isZero();
    }

    @Test
    @DisplayName("no payments in the window means no division by zero")
    void noPaymentsInWindow() {
        FeatureVector vector = extract(activity(List.of(), 0, 0, 0, 0));

        assertThat(vector.returnedPaymentFraction()).isZero();
        assertThat(vector.recentPaymentCount()).isZero();
        assertThat(vector.log10LargestRecentAmount()).isZero();
    }

    @Test
    @DisplayName("extraction is a pure function: the same activity gives the same vector")
    void extractionIsDeterministic() {
        List<AccountActivity.PaymentEvent> history = variedHistory(20, 10_000, 500);
        history.add(payment(5_000_000, NOW.minusSeconds(120)));
        AccountActivity activity = activity(history, 20, 3, 20, 2);

        assertThat(extract(activity).toArray())
                .as("training and scoring run this same code; if it varied, a model would "
                        + "score its own training data incorrectly and nothing would notice")
                .isEqualTo(extract(activity).toArray());
    }

    @Test
    @DisplayName("raw statistics reach the model, not Phase 8's saturated scores")
    void rawStatisticsNotNormalisedScores() {
        List<AccountActivity.PaymentEvent> history = variedHistory(20, 10_000, 500);
        history.add(payment(500_000_000L, NOW.minusSeconds(60)));
        AccountActivity activity = activity(history, 0, 0, 0, 0);

        AnomalyScore score = AnomalyScorer.withAllSignals(SETTINGS).score(activity);
        SignalScore amount = score.signals().stream()
                .filter(candidate -> candidate.signal() == Signal.AMOUNT_OUTLIER)
                .findFirst().orElseThrow();

        assertThat(amount.score())
                .as("Phase 8's published score has saturated and lost the magnitude")
                .isEqualTo(1.0);
        assertThat(Math.abs(extract(activity).amountModifiedZ()))
                .as("the model sees the statistic that still carries it")
                .isGreaterThan(Signal.AMOUNT_OUTLIER.saturation());
    }

    // --------------------------------------------------------------- helpers

    private static FeatureVector extract(AccountActivity activity) {
        return FeatureExtractor.extract(activity, AnomalyScorer.withAllSignals(SETTINGS).score(activity));
    }

    private static AccountActivity activity(List<AccountActivity.PaymentEvent> payments,
                                            long windowTransactions, long windowMismatches,
                                            long windowPayments, long windowReturned) {
        return new AccountActivity(
                UUID.randomUUID(), "USD", NOW,
                SETTINGS.recentWindow(), SETTINGS.baselineWindow(),
                payments, windowTransactions, windowMismatches, windowPayments, windowReturned,
                new AccountActivity.GlobalRates(10_000, 20, 10_000, 300));
    }

    private static AccountActivity.PaymentEvent payment(long amountMinor, Instant at) {
        return new AccountActivity.PaymentEvent(UUID.randomUUID(), amountMinor, at);
    }

    private static List<AccountActivity.PaymentEvent> dailyPayments(int count, long amountMinor) {
        List<AccountActivity.PaymentEvent> payments = new ArrayList<>();
        for (int i = count; i >= 1; i--) {
            payments.add(payment(amountMinor, NOW.minus(Duration.ofDays(i))));
        }
        return payments;
    }

    private static List<AccountActivity.PaymentEvent> variedHistory(int count, long base, long step) {
        List<AccountActivity.PaymentEvent> payments = new ArrayList<>();
        for (int i = count; i >= 1; i--) {
            long offset = (i % 2 == 0 ? 1 : -1) * step * ((i + 1) / 2);
            payments.add(payment(base + offset, NOW.minus(Duration.ofDays(i))));
        }
        return payments;
    }
}
