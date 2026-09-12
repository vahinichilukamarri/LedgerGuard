package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.AccountActivity;
import com.ledgerguard.detection.ActivityFixtures;
import com.ledgerguard.detection.AnomalyScore;
import com.ledgerguard.detection.AnomalyScorer;
import com.ledgerguard.detection.DetectionSettings;
import com.ledgerguard.detection.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The statistical half of the explanation.
 *
 * <p>The property that matters here is arithmetic rather than prose: the parts
 * must add up to the whole. A breakdown whose contributions do not sum to the
 * composite would be a plausible-looking table that quietly disagreed with the
 * number beside it, which is worse than publishing no breakdown at all.
 */
class StatisticalExplanationTest {

    private static final double TOLERANCE = 1e-12;

    @Test
    @DisplayName("contributions sum to the composite, exactly")
    void contributionsSumToTheComposite() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.AMOUNT_OUTLIER, 7.0);
        statistics.put(Signal.VELOCITY, 5.5);
        statistics.put(Signal.BURST, 4.0);

        StatisticalExplanation explanation =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics));

        double summed = explanation.contributions().stream()
                .mapToDouble(SignalContribution::contribution)
                .sum();

        assertThat(summed).isCloseTo(explanation.composite(), within(TOLERANCE));
    }

    @Test
    @DisplayName("the same signal contributes more when fewer signals could judge")
    void renormalisationIsVisible() {
        Map<Signal, Double> alone = new EnumMap<>(Signal.class);
        alone.put(Signal.AMOUNT_OUTLIER, 7.0);

        Map<Signal, Double> crowded = new EnumMap<>(alone);
        crowded.put(Signal.VELOCITY, 5.5);
        crowded.put(Signal.BURST, 4.0);

        double aloneContribution = contributionOf(
                StatisticalExplanation.of(ExplanationFixtures.score(alone)), "amount_outlier");
        double crowdedContribution = contributionOf(
                StatisticalExplanation.of(ExplanationFixtures.score(crowded)), "amount_outlier");

        assertThat(aloneContribution)
                .as("the whole composite rests on it when nothing else could judge")
                .isGreaterThan(crowdedContribution);
        assertThat(effectiveWeightOf(
                StatisticalExplanation.of(ExplanationFixtures.score(alone)), "amount_outlier"))
                .isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    @DisplayName("effective weight is the fixed weight over the applicable weight")
    void effectiveWeightIsRenormalised() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.AMOUNT_OUTLIER, 7.0);
        statistics.put(Signal.VELOCITY, 5.5);

        StatisticalExplanation explanation =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics));

        double applicableWeight = Signal.AMOUNT_OUTLIER.weight() + Signal.VELOCITY.weight();
        assertThat(explanation.applicableWeight()).isCloseTo(applicableWeight, within(TOLERANCE));
        assertThat(effectiveWeightOf(explanation, "amount_outlier"))
                .isCloseTo(Signal.AMOUNT_OUTLIER.weight() / applicableWeight, within(TOLERANCE));
    }

    /**
     * The case the phase brief names: anomalous on one axis must explain as
     * that axis, not as a general elevation.
     */
    @Test
    @DisplayName("an account anomalous only on amount explains as amount, not vaguely")
    void anomalousOnlyOnAmount() {
        AccountActivity activity = ActivityFixtures.account()
                .withVariedHistory(40, 10_000, 250)
                .withRecentPayment(4_000_000, 10)
                .build();

        AnomalyScore score = AnomalyScorer.withAllSignals(DetectionSettings.defaults()).score(activity);
        StatisticalExplanation explanation = StatisticalExplanation.of(score);

        assertThat(explanation.drivers()).isNotEmpty();
        assertThat(explanation.drivers().get(0).signal())
                .as("the top driver names the axis, so a reviewer knows what to open")
                .isEqualTo("amount_outlier");
        assertThat(explanation.drivers().get(0).explanation())
                .as("and the prose is the signal's own, from the code that did the arithmetic")
                .contains("robust deviations");

        assertThat(explanation.contributions().stream()
                .mapToDouble(SignalContribution::contribution)
                .sum())
                .isCloseTo(explanation.composite(), within(TOLERANCE));
    }

    @Test
    @DisplayName("signals that could not judge stay in the list, contributing nothing")
    void unmeasurableSignalsAreNotDropped() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.AMOUNT_OUTLIER, 7.0);

        StatisticalExplanation explanation =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics));

        assertThat(explanation.contributions())
                .as("all five, always: an absent row would read as a signal that was quiet")
                .hasSize(Signal.values().length);
        assertThat(explanation.unmeasurable()).hasSize(Signal.values().length - 1);
        assertThat(explanation.unmeasurable())
                .allSatisfy(contribution -> {
                    assertThat(contribution.contribution()).isZero();
                    assertThat(contribution.effectiveWeight()).isZero();
                    assertThat(contribution.statistic())
                            .as("null, not zero: they are different claims")
                            .isNull();
                    assertThat(contribution.explanation()).startsWith("insufficient data");
                });
    }

    @Test
    @DisplayName("contributions are ordered worst first, which is the reading order")
    void orderedByContribution() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.REFUND_REVERSAL_RATE, 7.5);
        statistics.put(Signal.AMOUNT_OUTLIER, 4.0);
        statistics.put(Signal.VELOCITY, 3.2);

        List<SignalContribution> contributions =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics)).contributions();

        assertThat(contributions)
                .isSortedAccordingTo((left, right) ->
                        Double.compare(right.contribution(), left.contribution()));
        assertThat(contributions.get(0).signal()).isEqualTo("refund_reversal_rate");
    }

    @Test
    @DisplayName("an account nothing could judge explains as no evidence, not as innocence")
    void noApplicableSignals() {
        StatisticalExplanation explanation =
                StatisticalExplanation.of(ExplanationFixtures.score(new EnumMap<>(Signal.class)));

        assertThat(explanation.composite()).isZero();
        assertThat(explanation.applicableSignals()).isZero();
        assertThat(explanation.applicableWeight()).isZero();
        assertThat(explanation.wellEvidenced()).isFalse();
        assertThat(explanation.drivers()).isEmpty();
        assertThat(explanation.unmeasurable()).hasSize(Signal.values().length);
    }

    @ParameterizedTest
    @EnumSource(Signal.class)
    @DisplayName("every signal, fired alone, is attributed to itself")
    void eachSignalAttributesToItself(Signal signal) {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(signal, signal.saturation());

        StatisticalExplanation explanation =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics));

        assertThat(explanation.drivers()).hasSize(1);
        assertThat(explanation.drivers().get(0).signal()).isEqualTo(signal.wireName());
        assertThat(explanation.drivers().get(0).contribution()).isCloseTo(1.0, within(TOLERANCE));
    }

    private static double contributionOf(StatisticalExplanation explanation, String signal) {
        return explanation.contributions().stream()
                .filter(contribution -> contribution.signal().equals(signal))
                .mapToDouble(SignalContribution::contribution)
                .findFirst()
                .orElseThrow();
    }

    private static double effectiveWeightOf(StatisticalExplanation explanation, String signal) {
        return explanation.contributions().stream()
                .filter(contribution -> contribution.signal().equals(signal))
                .mapToDouble(SignalContribution::effectiveWeight)
                .findFirst()
                .orElseThrow();
    }
}
