package com.ledgerguard.detection;

import com.ledgerguard.detection.ml.Agreement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How the five signals combine.
 *
 * <p>Rewritten in Phase 13, which replaced the renormalised weighted mean these
 * tests were written against. The three that asserted renormalisation now
 * assert the properties that replaced it; see {@link CompositeAggregation} for
 * the reasoning and {@code CompositeCeilingTest} for the exhaustive version.
 */
class AnomalyScorerTest {

    private static final DetectionSettings SETTINGS = DetectionSettings.defaults();

    /** A signal that returns whatever the test tells it to. */
    private record Stub(Signal signal, SignalScore outcome) implements AnomalySignal {
        @Override
        public SignalScore evaluate(AccountActivity activity, DetectionSettings settings) {
            return outcome;
        }
    }

    private static AnomalySignal firing(Signal signal, double normalisedScore) {
        // Pick a statistic that lands on the requested normalised score.
        double statistic = signal.threshold()
                + normalisedScore * (signal.saturation() - signal.threshold());
        return new Stub(signal, SignalScore.of(signal, statistic, "stub"));
    }

    private static AnomalySignal silent(Signal signal) {
        return new Stub(signal, SignalScore.insufficientData(signal, "stub"));
    }

    private static AccountActivity anyActivity() {
        return ActivityFixtures.account().build();
    }

    /**
     * Rewritten in Phase 13. This test used to assert that a lone saturated
     * signal contributes exactly its own weight — 0.25 for the amount signal —
     * which was true of the weighted mean and was also the defect: 0.25 is half
     * the elevation threshold, so the account could not be flagged.
     */
    @Test
    @DisplayName("with every signal applicable, one saturated signal still elevates")
    void oneSaturatedSignalElevatesAFullyMeasuredAccount() {
        AnomalyScorer scorer = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 1.0),
                firing(Signal.VELOCITY, 0.0),
                firing(Signal.BURST, 0.0),
                firing(Signal.RECONCILIATION_MISMATCH_RATE, 0.0),
                firing(Signal.REFUND_REVERSAL_RATE, 0.0)), SETTINGS);

        AnomalyScore score = scorer.score(anyActivity());

        assertThat(score.applicableSignals()).isEqualTo(5);
        assertThat(score.composite())
                .as("the cube root of the signal's weight, which clears the 0.50 convention")
                .isCloseTo(Math.cbrt(Signal.AMOUNT_OUTLIER.weight()), within(1e-9));
        assertThat(score.composite()).isGreaterThanOrEqualTo(Agreement.STATISTICAL_ELEVATED);
    }

    /**
     * The renormalisation is gone, and this is the test that used to assert it.
     *
     * <p>It now asserts the opposite property, which is the point of Phase 13:
     * what the other signals could or could not measure makes no difference to
     * what this one contributes. See {@code CompositeCeilingTest} for the
     * exhaustive version and {@link CompositeAggregation} for why.
     */
    @Test
    @DisplayName("the composite does not depend on how many signals could be measured")
    void measurabilityDoesNotChangeTheScore() {
        AnomalyScorer fullyMeasured = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 1.0),
                firing(Signal.VELOCITY, 0.0),
                firing(Signal.BURST, 0.0),
                firing(Signal.RECONCILIATION_MISMATCH_RATE, 0.0),
                firing(Signal.REFUND_REVERSAL_RATE, 0.0)), SETTINGS);

        AnomalyScorer thinHistory = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 1.0),
                firing(Signal.VELOCITY, 0.0),
                silent(Signal.BURST),
                silent(Signal.RECONCILIATION_MISMATCH_RATE),
                silent(Signal.REFUND_REVERSAL_RATE)), SETTINGS);

        AnomalyScore measured = fullyMeasured.score(anyActivity());
        AnomalyScore thin = thinHistory.score(anyActivity());

        assertThat(thin.applicableSignals()).isEqualTo(2);
        assertThat(measured.applicableSignals()).isEqualTo(5);
        assertThat(thin.composite())
                .as("the same behaviour, judged against less evidence, is the same score; "
                        + "how much evidence there was lives in applicableSignals")
                .isCloseTo(measured.composite(), within(1e-9));
    }

    @Test
    @DisplayName("one applicable signal firing hard is elevated without claiming certainty")
    void singleSignalSaturatesButIsMarkedThinlyEvidenced() {
        AnomalyScorer scorer = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 1.0),
                silent(Signal.VELOCITY),
                silent(Signal.BURST),
                silent(Signal.RECONCILIATION_MISMATCH_RATE),
                silent(Signal.REFUND_REVERSAL_RATE)), SETTINGS);

        AnomalyScore score = scorer.score(anyActivity());

        // Phases 8 to 12 reported 1.0 here: total certainty from one signal,
        // documented as a known wart. Phase 13 retired it.
        assertThat(score.composite())
                .isGreaterThanOrEqualTo(Agreement.STATISTICAL_ELEVATED)
                .isLessThan(0.8);
        assertThat(score.applicableSignals()).isEqualTo(1);
        assertThat(score.isWellEvidenced())
                .as("thin evidence is still called thin, in the field built to say so")
                .isFalse();
    }

    @Test
    @DisplayName("no applicable signal is an absence of evidence, not a clean bill of health")
    void nothingMeasurable() {
        AnomalyScorer scorer = new AnomalyScorer(List.of(
                silent(Signal.AMOUNT_OUTLIER),
                silent(Signal.VELOCITY),
                silent(Signal.BURST),
                silent(Signal.RECONCILIATION_MISMATCH_RATE),
                silent(Signal.REFUND_REVERSAL_RATE)), SETTINGS);

        AnomalyScore score = scorer.score(anyActivity());

        assertThat(score.composite()).isZero();
        assertThat(score.applicableSignals()).isZero();
        assertThat(score.isWellEvidenced()).isFalse();
        assertThat(score.firedSignals()).isEmpty();
        assertThat(score.signals())
                .as("every signal still reports, so the absence is inspectable")
                .hasSize(5);
    }

    @Test
    @DisplayName("everything at full scale is exactly one")
    void allSignalsSaturated() {
        AnomalyScorer scorer = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 1.0),
                firing(Signal.VELOCITY, 1.0),
                firing(Signal.BURST, 1.0),
                firing(Signal.RECONCILIATION_MISMATCH_RATE, 1.0),
                firing(Signal.REFUND_REVERSAL_RATE, 1.0)), SETTINGS);

        assertThat(scorer.score(anyActivity()).composite()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("fired signals come back worst first, for the investigator to read in order")
    void firedSignalsAreRankedByContribution() {
        AnomalyScorer scorer = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 0.3),
                firing(Signal.VELOCITY, 0.9),
                firing(Signal.BURST, 0.0),
                silent(Signal.RECONCILIATION_MISMATCH_RATE),
                firing(Signal.REFUND_REVERSAL_RATE, 0.6)), SETTINGS);

        List<SignalScore> fired = scorer.score(anyActivity()).firedSignals();

        assertThat(fired).extracting(SignalScore::signal)
                .containsExactly(Signal.VELOCITY, Signal.REFUND_REVERSAL_RATE, Signal.AMOUNT_OUTLIER);
    }

    @Test
    @DisplayName("the real signal set is fixed in order, so two runs agree exactly")
    void realSignalSetIsDeterministic() {
        AccountActivity activity = ActivityFixtures.account()
                .withVariedHistory(20, 10_000, 500)
                .withRecentPayment(5_000_000, 10)
                .withWindowTransactions(20, 15)
                .withWindowPayments(20, 16)
                .withGlobalRates(10_000, 20, 10_000, 300)
                .build();

        AnomalyScore first = AnomalyScorer.withAllSignals(SETTINGS).score(activity);
        AnomalyScore second = AnomalyScorer.withAllSignals(SETTINGS).score(activity);

        assertThat(first.composite()).isEqualTo(second.composite());
        assertThat(first.signals()).extracting(SignalScore::signal)
                .containsExactlyElementsOf(second.signals().stream().map(SignalScore::signal).toList());
    }
}
