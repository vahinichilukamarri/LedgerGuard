package com.ledgerguard.detection.explain.llm;

import com.ledgerguard.detection.explain.ForbiddenVocabulary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate between a model's output and a reviewer's screen.
 *
 * <p>These are the tests that decide whether this phase is safe. Everything
 * else — the client, the breaker, the cache — is ordinary plumbing whose worst
 * failure is a template. A validator that passes a fabricated number puts a
 * false statement about someone's account in front of a human who has no way
 * to know it is false.
 */
class NarrativeValidatorTest {

    private final NarrativeValidator validator = new NarrativeValidator();

    private static NarrativeEvidence elevated() {
        return NarrativeEvidence.of(LlmFixtures.bothElevated());
    }

    private ValidationResult validate(String narrative) {
        return validator.validate(narrative, elevated());
    }

    private static List<ValidationResult.Reason> reasonsOf(ValidationResult result) {
        return result.failures().stream().map(ValidationResult.Failure::reason).toList();
    }

    @Test
    @DisplayName("a narrative that only restates its evidence passes")
    void validNarrativePasses() {
        ValidationResult result = validate(LlmFixtures.VALID_NARRATIVE);

        assertThat(result.valid())
                .as("rejected for: %s", result.summary())
                .isTrue();
        assertThat(result.failures()).isEmpty();
    }

    // --------------------------------------------------------- fabrication

    @Test
    @DisplayName("a feature name that does not exist anywhere is caught as fabricated")
    void fabricatedFeatureName() {
        ValidationResult result = validate(LlmFixtures.VALID_NARRATIVE.replace(
                "amountModifiedZ", "velocityZScore"));

        assertThat(result.valid()).isFalse();
        assertThat(reasonsOf(result)).contains(ValidationResult.Reason.FABRICATED_IDENTIFIER);
        assertThat(result.summary()).contains("velocityZScore");
    }

    /**
     * The subtler half of the same problem: a real feature, spelled correctly,
     * that this account's evidence never carried. It reads entirely plausibly,
     * which is exactly why the check cannot be "is this a known name".
     */
    @Test
    @DisplayName("a real feature the evidence never mentioned is caught as ungrounded")
    void realButUngroundedFeatureName() {
        ValidationResult result = validate(LlmFixtures.VALID_NARRATIVE.replace(
                "amountModifiedZ", "burstSurprisal"));

        assertThat(result.valid()).isFalse();
        assertThat(reasonsOf(result)).contains(ValidationResult.Reason.UNGROUNDED_IDENTIFIER);
    }

    @Test
    @DisplayName("a signal name from the evidence is allowed, spelled as the evidence spells it")
    void groundedSignalNamePasses() {
        assertThat(validate(LlmFixtures.VALID_NARRATIVE).valid()).isTrue();
    }

    // ------------------------------------------------------------- numbers

    @Test
    @DisplayName("a number that is in no evidence value is caught")
    void fabricatedNumber() {
        ValidationResult result = validate(LlmFixtures.VALID_NARRATIVE.replace(
                "120 training accounts", "137 training accounts"));

        assertThat(result.valid()).isFalse();
        assertThat(reasonsOf(result)).contains(ValidationResult.Reason.UNGROUNDED_NUMBER);
        assertThat(result.summary()).contains("137");
    }

    @Test
    @DisplayName("a score subtly off by one decimal place is caught")
    void subtlyWrongScore() {
        ValidationResult result = validate(LlmFixtures.VALID_NARRATIVE.replace(
                "model scores 0.9", "model scores 0.7"));

        assertThat(result.valid()).isFalse();
        assertThat(reasonsOf(result)).contains(ValidationResult.Reason.UNGROUNDED_NUMBER);
    }

    @Test
    @DisplayName("a truthful rounding of an evidence value is allowed")
    void roundedRenderingPasses() {
        // The composite rounds to 0.63 in the evidence, so 0.63 and 0.630 are
        // the same claim written two ways; a validator that rejected the second
        // would reject correct prose for choosing a different number of digits.
        assertThat(validate(LlmFixtures.VALID_NARRATIVE.replace("is 0.63 over", "is 0.630 over"))
                .valid()).isTrue();
    }

    @Test
    @DisplayName("percentages of a [0,1] evidence value are allowed")
    void percentageRenderingPasses() {
        assertThat(validate(LlmFixtures.VALID_NARRATIVE).valid())
                .as("100%% of the isolation and the 99th percentile are both percent forms")
                .isTrue();
    }

    /**
     * The regression that took a second pass: a digit inside an identifier is
     * not a claim about anything, and treating it as one rejected every correct
     * narrative that named the dormancy feature.
     */
    @Test
    @DisplayName("digits inside a feature name are not read as numeric claims")
    void digitsInsideIdentifiersAreNotClaims() {
        NarrativeEvidence evidence = NarrativeEvidence.of(
                com.ledgerguard.detection.explain.AccountExplanation.of(
                        com.ledgerguard.detection.explain.ExplanationFixtures.statisticalScore(0.8),
                        java.util.Optional.of(com.ledgerguard.detection.explain.ExplanationFixtures
                                .mlExplanation(0.9, "log10SecondsSinceLastPayment")),
                        "n/a"));

        String narrative = """
                The statistical composite is 0.63 over 1 applicable signal, driven by amount_outlier \
                at 100% of the score. The model scores 0.9 against 120 training accounts and \
                isolated this account on log10SecondsSinceLastPayment, 100% of the isolation at the \
                99th percentile. Both layers are elevated.""";

        ValidationResult result = validator.validate(narrative, evidence);

        assertThat(result.valid())
                .as("rejected for: %s", result.summary())
                .isTrue();
    }

    @Test
    @DisplayName("the scale constants a narrative needs to explain the scale are allowed")
    void scaleConstantsAreGrounded() {
        String narrative = LlmFixtures.VALID_NARRATIVE
                + " On this scale 0.5 is the middle of the distribution and 0.6 is the convention "
                + "for elevated.";

        assertThat(validator.validate(narrative, elevated()).valid()).isTrue();
    }

    // ---------------------------------------------------------- vocabulary

    @ParameterizedTest
    @ValueSource(strings = {"fraud", "suspicious", "confirmed", "definitely", "clearly indicates"})
    @DisplayName("words asserting wrongdoing or certainty are caught, wherever they appear")
    void forbiddenWords(String word) {
        ValidationResult result = validate(
                LlmFixtures.VALID_NARRATIVE + " This " + word + " matters.");

        assertThat(result.valid()).isFalse();
        assertThat(reasonsOf(result)).contains(ValidationResult.Reason.FORBIDDEN_WORD);
    }

    @Test
    @DisplayName("the validator and the prompt read the same list")
    void oneVocabularyList() {
        for (String word : ForbiddenVocabulary.WORDS) {
            assertThat(ForbiddenVocabulary.asPromptList()).contains(word);
            assertThat(validate(LlmFixtures.VALID_NARRATIVE + " " + word + ".").valid())
                    .as("'%s' is in the list the prompt states, so it must also be rejected", word)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------- structure

    @Test
    @DisplayName("chat preamble is not part of a narrative")
    void preamble() {
        ValidationResult result = validate("Here is a summary: " + LlmFixtures.VALID_NARRATIVE);

        assertThat(result.valid()).isFalse();
        assertThat(reasonsOf(result)).contains(ValidationResult.Reason.PREAMBLE);
    }

    @Test
    @DisplayName("markdown is caught where plain text was asked for")
    void markdown() {
        assertThat(reasonsOf(validate("## Summary\n\n" + LlmFixtures.VALID_NARRATIVE)))
                .contains(ValidationResult.Reason.MARKUP);
        assertThat(reasonsOf(validate(LlmFixtures.VALID_NARRATIVE + "\n- a bullet point here")))
                .contains(ValidationResult.Reason.MARKUP);
    }

    @Test
    @DisplayName("empty and near-empty completions are caught")
    void emptyAndShort() {
        assertThat(reasonsOf(validate(null))).containsExactly(ValidationResult.Reason.EMPTY);
        assertThat(reasonsOf(validate("   "))).containsExactly(ValidationResult.Reason.EMPTY);
        assertThat(reasonsOf(validate("Composite 0.8."))).contains(ValidationResult.Reason.TOO_SHORT);
    }

    @Test
    @DisplayName("a model that will not stop writing is caught")
    void tooLong() {
        assertThat(reasonsOf(validate(LlmFixtures.VALID_NARRATIVE.repeat(8))))
                .contains(ValidationResult.Reason.TOO_LONG);
    }

    // ----------------------------------------------------------------- state

    @Test
    @DisplayName("claiming both layers are elevated when they are not is caught")
    void contradictsAgreementState() {
        NarrativeEvidence quiet = NarrativeEvidence.of(LlmFixtures.bothQuiet());

        String narrative = """
                The statistical composite is 0.0 over 5 applicable signals and no signal fired. \
                The model scores 0.3 against 120 training accounts. Both layers are elevated and \
                they point at the same behaviour.""";

        ValidationResult result = validator.validate(narrative, quiet);

        assertThat(result.valid()).isFalse();
        assertThat(reasonsOf(result)).contains(ValidationResult.Reason.STATE_CONTRADICTION);
    }

    @Test
    @DisplayName("claiming corroboration where the layers share no axis is caught")
    void contradictsCorroboration() {
        NarrativeEvidence quiet = NarrativeEvidence.of(LlmFixtures.bothQuiet());

        String narrative = """
                The statistical composite is 0.0 over 5 applicable signals and no signal fired. \
                The model scores 0.3 against 120 training accounts. The layers corroborate one \
                another on this account.""";

        assertThat(reasonsOf(validator.validate(narrative, quiet)))
                .contains(ValidationResult.Reason.STATE_CONTRADICTION);
    }

    @Test
    @DisplayName("the matching claim is allowed in the state where it is true")
    void statePhrasesAreAllowedWhereTrue() {
        assertThat(validate(LlmFixtures.VALID_NARRATIVE).valid())
                .as("this evidence really is BOTH_ELEVATED and really does corroborate")
                .isTrue();
    }

    // -------------------------------------------------------------- reporting

    @Test
    @DisplayName("every failure is reported, not just the first")
    void allFailuresAreCollected() {
        ValidationResult result = validate(
                "Here is a summary: the account scores 137 on velocityZScore and is clearly "
                        + "indicates a problem, definitely. Both layers are quiet and calm here.");

        assertThat(result.failures().size())
                .as("one reason would say less about whether this model is subtly wrong "
                        + "or wholesale inventing")
                .isGreaterThan(2);
        assertThat(result.summary()).isNotBlank();
    }
}
