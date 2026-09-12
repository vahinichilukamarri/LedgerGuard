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
    @DisplayName("contributions sum to one, exactly")
    void contributionsSumToOne() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.AMOUNT_OUTLIER, 7.0);
        statistics.put(Signal.VELOCITY, 5.5);
        statistics.put(Signal.BURST, 4.0);

        StatisticalExplanation explanation =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics));

        double summed = explanation.contributions().stream()
                .mapToDouble(SignalContribution::contribution)
                .sum();

        // Phases 10 to 12 asserted this summed to the composite, which held
        // while the composite was a weighted arithmetic mean. Phase 13's power
        // mean has parts that sum to the composite cubed, so the breakdown
        // publishes shares and the identity is that shares sum to one.
        assertThat(summed).isCloseTo(1.0, within(TOLERANCE));
    }

    /**
     * Rewritten in Phase 13. This pair used to assert that a signal's
     * contribution grew when fewer signals could judge, which was the
     * renormalisation showing through — and the mechanism behind the composite
     * ceiling. The contribution is now a share of what actually fired, so it
     * moves with the other signals' <em>scores</em> and not with their
     * measurability.
     */
    @Test
    @DisplayName("a share moves with what else fired, not with what else could be measured")
    void sharesDependOnFiringNotMeasurability() {
        Map<Signal, Double> alone = new EnumMap<>(Signal.class);
        alone.put(Signal.AMOUNT_OUTLIER, 7.0);

        Map<Signal, Double> quietCompany = new EnumMap<>(alone);
        quietCompany.put(Signal.VELOCITY, 0.0);
        quietCompany.put(Signal.BURST, 0.0);

        Map<Signal, Double> loudCompany = new EnumMap<>(alone);
        loudCompany.put(Signal.VELOCITY, 5.5);
        loudCompany.put(Signal.BURST, 4.0);

        double onlyApplicable = contributionOf(
                StatisticalExplanation.of(ExplanationFixtures.score(alone)), "amount_outlier");
        double withQuietNeighbours = contributionOf(
                StatisticalExplanation.of(ExplanationFixtures.score(quietCompany)), "amount_outlier");
        double withLoudNeighbours = contributionOf(
                StatisticalExplanation.of(ExplanationFixtures.score(loudCompany)), "amount_outlier");

        assertThat(withQuietNeighbours)
                .as("two neighbours that could judge and found nothing take no share")
                .isCloseTo(onlyApplicable, within(TOLERANCE));
        assertThat(withLoudNeighbours)
                .as("two neighbours that fired do")
                .isLessThan(onlyApplicable);
    }

    @Test
    @DisplayName("the applicable weight is still reported, as a statement rather than a divisor")
    void applicableWeightIsStillPublished() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.AMOUNT_OUTLIER, 7.0);
        statistics.put(Signal.VELOCITY, 5.5);

        StatisticalExplanation explanation =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics));

        assertThat(explanation.applicableWeight())
                .as("how much of the signal weight was able to speak, which is worth knowing "
                        + "even though the composite no longer divides by it")
                .isCloseTo(Signal.AMOUNT_OUTLIER.weight() + Signal.VELOCITY.weight(),
                        within(TOLERANCE));
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
                .isCloseTo(1.0, within(TOLERANCE));
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
        assertThat(explanation.drivers().get(0).contribution())
                .as("the only signal that fired supplied all of the score")
                .isCloseTo(1.0, within(TOLERANCE));
        assertThat(explanation.composite())
                .as("and the score itself is the cube root of that signal's weight")
                .isCloseTo(Math.cbrt(signal.weight()), within(TOLERANCE));
    }

    private static double contributionOf(StatisticalExplanation explanation, String signal) {
        return explanation.contributions().stream()
                .filter(contribution -> contribution.signal().equals(signal))
                .mapToDouble(SignalContribution::contribution)
                .findFirst()
                .orElseThrow();
    }

}
