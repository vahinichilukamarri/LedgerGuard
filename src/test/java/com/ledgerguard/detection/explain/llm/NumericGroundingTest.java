package com.ledgerguard.detection.explain.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Numbers as prose writes them.
 *
 * <p>The whole validator rests on this being neither too strict nor too loose.
 * Too strict rejects correct narratives for choosing two decimals instead of
 * three, which costs a worse summary on every good request; too loose lets a
 * fabricated figure through, which is the failure this phase exists to
 * prevent.
 */
class NumericGroundingTest {

    private static boolean grounded(String text, Double... evidence) {
        return NumericGrounding.statedIn(text).stream()
                .allMatch(stated -> NumericGrounding.isGrounded(stated, List.of(evidence)));
    }

    @Test
    @DisplayName("an exact match grounds")
    void exact() {
        assertThat(grounded("the composite is 0.556", 0.556)).isTrue();
    }

    @Test
    @DisplayName("a value rounded to the precision the narrative chose grounds")
    void roundedToStatedPrecision() {
        assertThat(grounded("the composite is 0.56", 0.556)).isTrue();
        assertThat(grounded("the composite is 0.6", 0.556)).isTrue();
        assertThat(grounded("the composite is 1", 0.556)).isTrue();
    }

    @Test
    @DisplayName("a value that rounds to something else does not ground")
    void wrongValue() {
        assertThat(grounded("the composite is 0.58", 0.556)).isFalse();
        assertThat(grounded("the composite is 0.4", 0.556)).isFalse();
    }

    @Test
    @DisplayName("a proportion grounds its percentage")
    void percentages() {
        assertThat(grounded("70% of the isolation", 0.7)).isTrue();
        assertThat(grounded("the 99th percentile", 0.99)).isTrue();
        assertThat(grounded("the 71st percentile", 0.7)).isFalse();
    }

    @Test
    @DisplayName("counts and thousands separators are read as numbers")
    void countsAndSeparators() {
        assertThat(grounded("230 training accounts", 230.0)).isTrue();
        assertThat(grounded("1,250 payments", 1250.0)).isTrue();
    }

    /** Phase 8's own signal sentences carry p-values, and quoting one is quoting the evidence. */
    @Test
    @DisplayName("scientific notation is read as a number")
    void scientificNotation() {
        assertThat(grounded("p = 2.41e-14", 2.41e-14)).isTrue();
        assertThat(grounded("p = 2.41e-14", 2.41e-13)).isFalse();
    }

    @Test
    @DisplayName("negative values are read with their sign")
    void negatives() {
        assertThat(grounded("excess bits of -0.42", -0.42)).isTrue();
        assertThat(grounded("excess bits of -0.42", 0.42)).isFalse();
    }

    @Test
    @DisplayName("text with no numbers grounds trivially")
    void noNumbers() {
        assertThat(NumericGrounding.statedIn("no figures at all here")).isEmpty();
        assertThat(grounded("no figures at all here")).isTrue();
    }

    @Test
    @DisplayName("the precision the narrative chose is what gets recorded")
    void decimalsAreRecorded() {
        List<NumericGrounding.Stated> stated = NumericGrounding.statedIn("0.5 and 0.50 and 7");

        assertThat(stated).extracting(NumericGrounding.Stated::decimals)
                .containsExactly(1, 2, 0);
        assertThat(stated).extracting(NumericGrounding.Stated::token)
                .containsExactly("0.5", "0.50", "7");
    }

    @Test
    @DisplayName("values are extracted in the order they appear")
    void valuesInOrder() {
        assertThat(NumericGrounding.valuesIn("first 1.5, then 2, then 3.25"))
                .containsExactly(1.5, 2.0, 3.25);
    }
}
