package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.ml.MlExplanation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated prose.
 *
 * <p>The vocabulary test is the important one. Everything else here checks that
 * the summary says enough; that one checks it does not say too much. Neither
 * score in this system is validated against known outcomes, so a sentence
 * calling an account fraudulent would be a claim nothing in the codebase has
 * earned, and the templates exist partly so that sentence is unreachable rather
 * than merely unlikely.
 */
class SummaryWriterTest {

    /**
     * Words that assert wrongdoing or certainty. An explanation may describe a
     * departure from a reference; it may not characterise the account.
     *
     * <p>Taken from {@link ForbiddenVocabulary} rather than restated here.
     * Phase 11 states the same list inside the LLM prompt and checks it again
     * after generation, and three copies would drift silently in the worst
     * direction: a word dropped from the validator but kept here would let
     * exactly one thing through.
     */
    private static final List<String> FORBIDDEN = ForbiddenVocabulary.WORDS;

    @ParameterizedTest
    @ValueSource(strings = {"amountModifiedZ", "log10SecondsSinceLastPayment", "burstSurprisal"})
    @DisplayName("no summary characterises the account, whatever it was isolated on")
    void neverOverstates(String driver) {
        String summary = summaryFor(0.9, 0.9, driver).toLowerCase();

        assertThat(FORBIDDEN)
                .allSatisfy(word -> assertThat(summary)
                        .as("the scores are unvalidated, so '%s' is a claim nothing here has earned", word)
                        .doesNotContain(word));
    }

    @Test
    @DisplayName("every summary closes by saying what it does not establish")
    void carriesItsOwnQualification() {
        assertThat(summaryFor(0.9, 0.9, "amountModifiedZ"))
                .contains("Neither score is validated")
                .contains("none of it establishes that the activity is illegitimate");
    }

    @Test
    @DisplayName("the statistical sentence names the signals and what each contributed")
    void namesStatisticalDrivers() {
        Map<Signal, Double> statistics = new EnumMap<>(Signal.class);
        statistics.put(Signal.BURST, Signal.BURST.saturation());
        statistics.put(Signal.VELOCITY, Signal.VELOCITY.saturation());

        StatisticalExplanation statistical =
                StatisticalExplanation.of(ExplanationFixtures.score(statistics));
        MlExplanation ml = ExplanationFixtures.mlExplanation(0.9, "burstSurprisal");

        String summary = SummaryWriter.summarise(
                statistical, ml, Reconciliation.of(statistical, ml),
                ExplanationFixtures.NO_MODEL_REASON);

        assertThat(summary)
                .contains("burst")
                .contains("velocity")
                .contains("of the score");
    }

    @Test
    @DisplayName("the model sentence gives the scale, the driver, its share and its percentile")
    void namesModelDrivers() {
        String summary = summaryFor(0.2, 0.88, "log10LargestRecentAmount");

        assertThat(summary)
                .contains("log10LargestRecentAmount")
                .contains("of the isolation")
                .contains("percentile")
                .contains("0.5 is the middle of the distribution rather than a threshold");
    }

    @Test
    @DisplayName("with no model trained the summary says so, and explains only one layer")
    void noModel() {
        String summary = SummaryWriter.summarise(
                StatisticalExplanation.of(ExplanationFixtures.statisticalScore(0.7)),
                null,
                null,
                ExplanationFixtures.NO_MODEL_REASON);

        assertThat(summary)
                .contains("No model has been trained")
                .contains(ExplanationFixtures.NO_MODEL_REASON)
                .doesNotContain("isolation score");
    }

    @Test
    @DisplayName("an account no signal fired on is described as such, not as quiet-and-fine")
    void noSignalsFired() {
        String summary = SummaryWriter.summarise(
                StatisticalExplanation.of(ExplanationFixtures.quietScore()),
                null,
                null,
                ExplanationFixtures.NO_MODEL_REASON);

        assertThat(summary).contains("No statistical signal fired");
    }

    @Test
    @DisplayName("the reconciliation narrative is carried into the summary, not paraphrased")
    void includesTheReconciliation() {
        StatisticalExplanation statistical =
                StatisticalExplanation.of(ExplanationFixtures.quietScore());
        MlExplanation ml = ExplanationFixtures.mlExplanation(0.9, "log10SecondsSinceLastPayment");
        Reconciliation reconciliation = Reconciliation.of(statistical, ml);

        assertThat(SummaryWriter.summarise(statistical, ml, reconciliation,
                ExplanationFixtures.NO_MODEL_REASON))
                .as("one wording of the disagreement, not two that can drift apart")
                .contains(reconciliation.narrative());
    }

    // ------------------------------------------------------------- caveats

    @Test
    @DisplayName("the standing caveats travel with every explanation")
    void standingCaveats() {
        List<String> caveats = SummaryWriter.caveats(
                StatisticalExplanation.of(ExplanationFixtures.statisticalScore(0.7)),
                ExplanationFixtures.mlExplanation(0.9, "amountModifiedZ"));

        assertThat(caveats).anySatisfy(caveat ->
                assertThat(caveat).contains("Neither score is validated"));
        assertThat(caveats).anySatisfy(caveat ->
                assertThat(caveat).contains("unfitted judgement"));
        assertThat(caveats).anySatisfy(caveat ->
                assertThat(caveat).contains("not a unique decomposition"));
        assertThat(caveats).anySatisfy(caveat ->
                assertThat(caveat).contains("Percentiles are context"));
    }

    @Test
    @DisplayName("a thin-evidence composite says so in its own caveat")
    void thinEvidenceCaveat() {
        // One applicable signal: renormalisation makes this a 0.7 composite on
        // a single piece of evidence, which is the case Phase 8 warns about.
        List<String> caveats = SummaryWriter.caveats(
                StatisticalExplanation.of(ExplanationFixtures.statisticalScore(0.7)), null);

        assertThat(caveats).anySatisfy(caveat ->
                assertThat(caveat).contains("rests on 1 applicable signal"));
        assertThat(caveats).anySatisfy(caveat ->
                assertThat(caveat).contains("absence of evidence"));
    }

    @Test
    @DisplayName("a fully measured account carries no thin-evidence caveat")
    void noThinEvidenceCaveatWhenEverythingWasMeasured() {
        List<String> caveats =
                SummaryWriter.caveats(StatisticalExplanation.of(ExplanationFixtures.quietScore()), null);

        assertThat(caveats).noneSatisfy(caveat ->
                assertThat(caveat).contains("applicable signal(s). Below two"));
        assertThat(caveats).noneSatisfy(caveat ->
                assertThat(caveat).contains("absence of evidence"));
    }

    @Test
    @DisplayName("model-specific caveats appear only when there is a model")
    void noModelCaveatsWithoutAModel() {
        List<String> caveats =
                SummaryWriter.caveats(StatisticalExplanation.of(ExplanationFixtures.quietScore()), null);

        assertThat(caveats).noneSatisfy(caveat ->
                assertThat(caveat).contains("Percentiles are context"));
    }

    @Test
    @DisplayName("percentiles read as ordinals rather than as bare fractions")
    void ordinals() {
        assertThat(SummaryWriter.ordinal(0.99)).isEqualTo("99th");
        assertThat(SummaryWriter.ordinal(0.01)).isEqualTo("1st");
        assertThat(SummaryWriter.ordinal(0.02)).isEqualTo("2nd");
        assertThat(SummaryWriter.ordinal(0.03)).isEqualTo("3rd");
        assertThat(SummaryWriter.ordinal(0.11)).isEqualTo("11th");
        assertThat(SummaryWriter.ordinal(1.0)).isEqualTo("100th");
    }

    private static String summaryFor(double composite, double mlScore, String driver) {
        StatisticalExplanation statistical =
                StatisticalExplanation.of(ExplanationFixtures.statisticalScore(composite));
        MlExplanation ml = ExplanationFixtures.mlExplanation(mlScore, driver);
        return SummaryWriter.summarise(statistical, ml, Reconciliation.of(statistical, ml),
                ExplanationFixtures.NO_MODEL_REASON);
    }
}
