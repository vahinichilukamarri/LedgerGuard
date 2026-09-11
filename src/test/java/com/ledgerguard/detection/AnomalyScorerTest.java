package com.ledgerguard.detection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How the five signals combine, including the case the renormalisation is a
 * trade-off about.
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

    @Test
    @DisplayName("with every signal applicable the composite is the plain weighted mean")
    void weightedMeanWhenAllApplicable() {
        AnomalyScorer scorer = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 1.0),
                firing(Signal.VELOCITY, 0.0),
                firing(Signal.BURST, 0.0),
                firing(Signal.RECONCILIATION_MISMATCH_RATE, 0.0),
                firing(Signal.REFUND_REVERSAL_RATE, 0.0)), SETTINGS);

        AnomalyScore score = scorer.score(anyActivity());

        assertThat(score.applicableSignals()).isEqualTo(5);
        assertThat(score.composite())
                .as("the weights sum to one, so a single signal at full scale contributes its weight")
                .isCloseTo(Signal.AMOUNT_OUTLIER.weight(), within(1e-9));
    }

    /**
     * The renormalisation, stated as a test because it is the scorer's one
     * genuinely debatable decision.
     */
    @Test
    @DisplayName("the composite divides by the weight that could be measured, not the whole weight")
    void renormalisesOverApplicableSignals() {
        AnomalyScorer scorer = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 1.0),
                firing(Signal.VELOCITY, 0.0),
                silent(Signal.BURST),
                silent(Signal.RECONCILIATION_MISMATCH_RATE),
                silent(Signal.REFUND_REVERSAL_RATE)), SETTINGS);

        AnomalyScore score = scorer.score(anyActivity());

        double applicableWeight = Signal.AMOUNT_OUTLIER.weight() + Signal.VELOCITY.weight();
        assertThat(score.applicableSignals()).isEqualTo(2);
        assertThat(score.composite())
                .as("without renormalising, a thin-history account could never exceed 0.45 "
                        + "however extreme its behaviour")
                .isCloseTo(Signal.AMOUNT_OUTLIER.weight() / applicableWeight, within(1e-9));
    }

    @Test
    @DisplayName("one applicable signal firing hard reads 1.0, and says so out loud")
    void singleSignalSaturatesButIsMarkedThinlyEvidenced() {
        AnomalyScorer scorer = new AnomalyScorer(List.of(
                firing(Signal.AMOUNT_OUTLIER, 1.0),
                silent(Signal.VELOCITY),
                silent(Signal.BURST),
                silent(Signal.RECONCILIATION_MISMATCH_RATE),
                silent(Signal.REFUND_REVERSAL_RATE)), SETTINGS);

        AnomalyScore score = scorer.score(anyActivity());

        assertThat(score.composite()).isEqualTo(1.0);
        assertThat(score.applicableSignals()).isEqualTo(1);
        assertThat(score.isWellEvidenced())
                .as("the cost of renormalising is visible rather than hidden inside the number")
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
