package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.MlExplanation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The disagreement output, across all four agreement states.
 *
 * <p>The requirement these tests encode is negative as much as positive: the
 * reconciliation must not produce one smooth narrative that reads the same
 * whether the layers agree or not. Each state gets its own assertion that the
 * words actually differ, and the two disagreement states are checked for saying
 * <em>what</em> the layers disagreed about rather than merely that they did.
 */
class ReconciliationTest {

    /** Elevated on both scales, under Phase 9's conventions. */
    private static final double ELEVATED_STATISTICAL = 0.80;
    private static final double ELEVATED_ML = 0.85;
    private static final double QUIET_STATISTICAL = 0.20;
    private static final double QUIET_ML = 0.30;

    @Test
    @DisplayName("BOTH_QUIET says so, and does not call it a clean bill of health")
    void bothQuiet() {
        Reconciliation reconciliation = reconcile(QUIET_STATISTICAL, QUIET_ML, "burstSurprisal");

        assertThat(reconciliation.agreement()).isEqualTo(Agreement.BOTH_QUIET);
        assertThat(reconciliation.narrative())
                .contains("Neither layer is elevated")
                .contains("not a clean bill of health");
    }

    @Test
    @DisplayName("BOTH_ELEVATED on a shared axis reports corroboration, and names the axis")
    void bothElevatedAndCorroborating() {
        Reconciliation reconciliation = reconcile(
                ELEVATED_STATISTICAL, ELEVATED_ML, "amountModifiedZ");

        assertThat(reconciliation.agreement()).isEqualTo(Agreement.BOTH_ELEVATED);
        assertThat(reconciliation.corroborated()).isTrue();
        assertThat(reconciliation.corroboratedSignals()).containsExactly("amount_outlier");
        assertThat(reconciliation.narrative())
                .contains("point at the same behaviour")
                .contains("amount_outlier")
                .contains("amountModifiedZ");
    }

    /**
     * The case that looks like agreement in the scores and is not. Two elevated
     * numbers resting on unrelated evidence must not read as two layers
     * confirming each other.
     */
    @Test
    @DisplayName("BOTH_ELEVATED on unrelated evidence is reported as not corroborating")
    void bothElevatedWithoutCorroboration() {
        Reconciliation reconciliation = reconcile(
                ELEVATED_STATISTICAL, ELEVATED_ML, "log10SecondsSinceLastPayment");

        assertThat(reconciliation.agreement()).isEqualTo(Agreement.BOTH_ELEVATED);
        assertThat(reconciliation.corroborated())
                .as("elevated together is not the same as elevated for the same reason")
                .isFalse();
        assertThat(reconciliation.corroboratedSignals()).isEmpty();
        assertThat(reconciliation.narrative())
                .contains("not on the same evidence")
                .contains("nothing here corroborates anything");
    }

    @Test
    @DisplayName("STATISTICAL_ONLY offers both readings rather than picking the flattering one")
    void statisticalOnly() {
        Reconciliation reconciliation = reconcile(
                ELEVATED_STATISTICAL, QUIET_ML, "amountModifiedZ");

        assertThat(reconciliation.agreement()).isEqualTo(Agreement.STATISTICAL_ONLY);
        assertThat(reconciliation.narrative())
                .contains("statistical layer is elevated and the model is not")
                .contains("Unusual for this account")
                .contains("the model's features do not capture what the signals caught");
    }

    @Test
    @DisplayName("ML_ONLY names the feature and says the statistical layer cannot see it")
    void mlOnlyOnSomethingInvisible() {
        Reconciliation reconciliation = reconcileQuietStatistics(
                ELEVATED_ML, "log10LargestRecentAmount");

        assertThat(reconciliation.agreement()).isEqualTo(Agreement.ML_ONLY);
        assertThat(reconciliation.modelDriversOutsideView())
                .containsExactly("log10LargestRecentAmount");
        assertThat(reconciliation.narrative())
                .contains("model is elevated and the statistical layer is not")
                .contains("log10LargestRecentAmount")
                .contains("does not see")
                .contains("nothing in the composite to corroborate or contradict");
    }

    /**
     * The other ML_ONLY case, and a genuinely different finding: the model and
     * the signals are looking at the same axis and reaching different answers.
     */
    @Test
    @DisplayName("ML_ONLY on an axis the signals do share says that instead")
    void mlOnlyOnASharedAxis() {
        Reconciliation reconciliation = reconcileQuietStatistics(ELEVATED_ML, "burstSurprisal");

        assertThat(reconciliation.agreement()).isEqualTo(Agreement.ML_ONLY);
        assertThat(reconciliation.modelDriversOutsideView()).isEmpty();
        assertThat(reconciliation.narrative())
                .contains("Every axis the model isolated on is one some statistical signal also looks at")
                .doesNotContain("does not see");
    }

    @Test
    @DisplayName("a same-axis feature corroborates a signal that fired, without being its statistic")
    void sameAxisCounts() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.VELOCITY, Signal.VELOCITY.saturation());

        Reconciliation reconciliation = Reconciliation.of(
                StatisticalExplanation.of(ExplanationFixtures.score(statistics)),
                ExplanationFixtures.mlExplanation(ELEVATED_ML, "recentPaymentCount"));

        assertThat(reconciliation.corroborated())
                .as("the raw count is a different measurement of the question velocity asks")
                .isTrue();
        assertThat(reconciliation.corroboratedSignals()).containsExactly("velocity");
    }

    @Test
    @DisplayName("a shared axis whose signal stayed quiet is not corroboration")
    void sharedAxisWithoutAFiringSignalIsNotCorroboration() {
        Reconciliation reconciliation = reconcileQuietStatistics(ELEVATED_ML, "velocitySurprisal");

        assertThat(reconciliation.corroborated())
                .as("the model isolating on an axis is not the signal agreeing with it")
                .isFalse();
        assertThat(reconciliation.corroboratedSignals()).isEmpty();
    }

    @Test
    @DisplayName("corroboration that covers only part of the model's reasoning says so")
    void partialCorroboration() {
        Reconciliation reconciliation = reconcile(
                ELEVATED_STATISTICAL, ELEVATED_ML, "amountModifiedZ", "log10LargestRecentAmount");

        assertThat(reconciliation.corroborated()).isTrue();
        assertThat(reconciliation.narrative())
                .as("the shared axis must not be allowed to stand for the whole explanation")
                .contains("The model also isolated on log10LargestRecentAmount")
                .contains("covers part of its reasoning rather than all of it");
    }

    /**
     * Not every ML_ONLY row is a disagreement: a signal can fire and still leave
     * the composite quiet, and reporting that as "the statistical layer did not
     * see it" would be wrong about a signal that saw it perfectly well.
     *
     * <p>Phase 13 narrowed this case sharply. It used to arise with a signal at
     * <em>full saturation</em>, because the weighted mean could not carry one
     * signal over the threshold however loud it was — that was the composite
     * ceiling, and it is now closed. Dilution requires a signal that is firing
     * but not saturated, which is what this fixture uses: a mismatch surprisal
     * of 6.0, three-fifths of the way up its scale.
     */
    @Test
    @DisplayName("ML_ONLY where the matching signal fired but did not carry the score")
    void mlOnlyByDilution() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.RECONCILIATION_MISMATCH_RATE, 6.0);
        statistics.put(Signal.AMOUNT_OUTLIER, 0.0);
        statistics.put(Signal.VELOCITY, 0.0);
        statistics.put(Signal.REFUND_REVERSAL_RATE, 0.0);

        StatisticalExplanation statistical =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics));
        Reconciliation reconciliation = Reconciliation.of(
                statistical, ExplanationFixtures.mlExplanation(ELEVATED_ML, "mismatchSurprisal"));

        assertThat(statistical.composite())
                .as("a partially firing signal does not carry the composite by itself")
                .isLessThan(Agreement.STATISTICAL_ELEVATED);
        assertThat(reconciliation.agreement()).isEqualTo(Agreement.ML_ONLY);
        assertThat(reconciliation.corroborated()).isTrue();
        assertThat(reconciliation.narrative())
                .contains("reconciliation_mismatch_rate did fire")
                .contains("over 4 applicable signals")
                .contains("did not carry it over the line");
    }

    @Test
    @DisplayName("ML_ONLY with no signal on the axis at all makes no dilution claim")
    void mlOnlyWithoutDilution() {
        assertThat(reconcileQuietStatistics(ELEVATED_ML, "log10LargestRecentAmount").narrative())
                .doesNotContain("diluted");
    }

    @Test
    @DisplayName("the four states produce four different narratives")
    void narrativesAreNotInterchangeable() {
        String bothQuiet = reconcile(QUIET_STATISTICAL, QUIET_ML, "burstSurprisal").narrative();
        String bothElevated = reconcile(ELEVATED_STATISTICAL, ELEVATED_ML, "amountModifiedZ").narrative();
        String statisticalOnly = reconcile(ELEVATED_STATISTICAL, QUIET_ML, "amountModifiedZ").narrative();
        String mlOnly = reconcileQuietStatistics(ELEVATED_ML, "amountModifiedZ").narrative();

        assertThat(java.util.Set.of(bothQuiet, bothElevated, statisticalOnly, mlOnly))
                .as("a narrative that read the same in every state would undo the whole design")
                .hasSize(4);
    }

    @Test
    @DisplayName("drivers from both layers travel alongside the prose, not only inside it")
    void driversAreStructuredAsWellAsNarrated() {
        Reconciliation reconciliation = reconcile(
                ELEVATED_STATISTICAL, ELEVATED_ML, "amountModifiedZ", "log10SecondsSinceLastPayment");

        assertThat(reconciliation.statisticalDrivers()).containsExactly("amount_outlier");
        assertThat(reconciliation.modelDrivers())
                .containsExactly("amountModifiedZ", "log10SecondsSinceLastPayment");
        assertThat(reconciliation.modelDriversOutsideView())
                .containsExactly("log10SecondsSinceLastPayment");
    }

    // --------------------------------------------------------------- helpers

    private static Reconciliation reconcile(double composite, double mlScore, String... drivers) {
        return Reconciliation.of(
                StatisticalExplanation.of(ExplanationFixtures.statisticalScore(composite)),
                ExplanationFixtures.mlExplanation(mlScore, drivers));
    }

    /** A statistically quiet account: every signal applicable, none firing. */
    private static Reconciliation reconcileQuietStatistics(double mlScore, String... drivers) {
        MlExplanation ml = ExplanationFixtures.mlExplanation(mlScore, drivers);
        return Reconciliation.of(
                StatisticalExplanation.of(ExplanationFixtures.quietScore()), ml);
    }
}
