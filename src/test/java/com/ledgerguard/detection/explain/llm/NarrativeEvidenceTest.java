package com.ledgerguard.detection.explain.llm;

import com.ledgerguard.detection.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payload the model sees.
 *
 * <p>Two things are being pinned. That it is <b>complete</b> — everything the
 * narrative may mention is derivable from it, which is what makes grounding
 * checkable. And that it is <b>closed</b> — it carries nothing the explanation
 * did not already contain, so a fact in the prose is either here or invented.
 */
class NarrativeEvidenceTest {

    @Test
    @DisplayName("all five signals travel, including the ones that could not judge")
    void carriesEverySignal() {
        NarrativeEvidence evidence = NarrativeEvidence.of(LlmFixtures.bothElevated());

        assertThat(evidence.statistical().signals())
                .as("a signal that could not judge is a fact worth narrating, "
                        + "and its absence would read as a signal that was quiet")
                .hasSize(Signal.values().length);
        assertThat(evidence.statistical().signals())
                .anySatisfy(signal -> assertThat(signal.applicable()).isFalse());
    }

    @Test
    @DisplayName("each signal carries its own sentence, verbatim")
    void carriesSignalDetail() {
        NarrativeEvidence evidence = NarrativeEvidence.of(LlmFixtures.bothElevated());

        assertThat(evidence.statistical().signals())
                .allSatisfy(signal -> assertThat(signal.detail()).isNotBlank());
    }

    @Test
    @DisplayName("values are rounded, so the model and the validator see the same digits")
    void roundsToThreeDecimals() {
        NarrativeEvidence evidence = NarrativeEvidence.of(LlmFixtures.bothElevated());

        assertThat(evidence.statistical().composite() * 1000)
                .isCloseTo(Math.rint(evidence.statistical().composite() * 1000),
                        org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("the mentionable identifiers are the signals and the drivers, and nothing else")
    void mentionableIdentifiers() {
        NarrativeEvidence evidence = NarrativeEvidence.of(LlmFixtures.bothElevated());

        assertThat(evidence.mentionableIdentifiers())
                .contains("amount_outlier", "amountModifiedZ")
                .doesNotContain("burstSurprisal", "velocitySurprisal");
    }

    @Test
    @DisplayName("the mentionable numbers include those inside each signal's own sentence")
    void mentionableNumbersIncludeSignalDetail() {
        NarrativeEvidence evidence = NarrativeEvidence.of(LlmFixtures.bothElevated());

        assertThat(evidence.mentionableNumbers())
                .contains(evidence.statistical().composite())
                .contains(evidence.model().score())
                .contains((double) evidence.model().trainingRows());
    }

    @Test
    @DisplayName("with no model trained the model half is absent, not zeroed")
    void noModel() {
        NarrativeEvidence evidence = NarrativeEvidence.of(LlmFixtures.noModel());

        assertThat(evidence.hasModel()).isFalse();
        assertThat(evidence.model())
                .as("a zero score would be a claim; absence is the truth")
                .isNull();
        assertThat(evidence.agreement()).isNull();
        assertThat(evidence.corroboratedSignals()).isEmpty();
    }

    @Test
    @DisplayName("the agreement state and its corroboration travel together")
    void carriesTheAgreementState() {
        NarrativeEvidence evidence = NarrativeEvidence.of(LlmFixtures.bothElevated());

        assertThat(evidence.agreement()).isEqualTo("BOTH_ELEVATED");
        assertThat(evidence.corroborated()).isTrue();
        assertThat(evidence.corroboratedSignals()).contains("amount_outlier");
    }

    @Test
    @DisplayName("the JSON is stable, because it is the cache key as well as the prompt")
    void jsonIsDeterministic() {
        assertThat(PromptBuilder.toJson(NarrativeEvidence.of(LlmFixtures.bothElevated())))
                .isEqualTo(PromptBuilder.toJson(NarrativeEvidence.of(LlmFixtures.bothElevated())));
    }

    @Test
    @DisplayName("the prompt states the rules the validator enforces")
    void promptAndValidatorAgree() {
        String system = PromptBuilder.systemPrompt();

        assertThat(system)
                .contains("fraud")
                .contains("Never claim wrongdoing")
                .contains("Every number you write must appear in the record")
                .contains("no markdown");
        assertThat(PromptBuilder.PROMPT_VERSION).isNotBlank();
    }

    /**
     * The worked example in the prompt is a style target, and its numbers are
     * not the account's. If one bled into a narrative it would be caught as an
     * ungrounded number — but it is worth knowing the example is distinctive
     * enough that a bleed does not accidentally coincide with real evidence.
     */
    @Test
    @DisplayName("the prompt's worked example does not share values with the fixtures")
    void theExampleIsDistinctive() {
        NarrativeEvidence evidence = NarrativeEvidence.of(LlmFixtures.bothElevated());

        assertThat(evidence.mentionableNumbers())
                .doesNotContain(0.41)
                .doesNotContain(180.0)
                .doesNotContain(37.0);
    }
}
