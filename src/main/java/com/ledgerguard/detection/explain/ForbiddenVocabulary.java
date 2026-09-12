package com.ledgerguard.detection.explain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Words a narrative about these scores may not contain.
 *
 * <h2>Why this is main code and not a test constant</h2>
 *
 * In Phase 10 this list lived in {@code SummaryWriterTest}, which was the right
 * place for it: the templates were fixed text, so the only thing that needed the
 * list was the test asserting the text stayed clean.
 *
 * <p>Phase 11 gives it two more readers. The list is now stated <em>inside the
 * prompt</em>, so the model is told the constraint rather than merely judged
 * against it, and it is checked again after generation before anything is
 * served. Three copies of a list like this would drift, and the drift would be
 * silent in the worst direction — a word dropped from the validator but left in
 * the test would let exactly one thing through.
 *
 * <h2>What these words have in common</h2>
 *
 * Each asserts either <b>wrongdoing</b> or <b>certainty</b>, and neither score in
 * this system has earned either. The detection layers measure departure from a
 * reference. They do not know what caused it and they have never been checked
 * against an outcome, so a narrative may describe the departure and may not
 * characterise the account.
 */
public final class ForbiddenVocabulary {

    /**
     * Matched case-insensitively as substrings, which is deliberately blunt:
     * "defrauded" should fail on "fraud" and a narrative has no legitimate
     * reason to contain the stem at all.
     */
    public static final List<String> WORDS = List.of(
            "fraud", "fraudulent", "criminal", "illegal", "launder", "suspicious",
            "malicious", "guilty", "confirmed", "proves", "proven", "certainly",
            "definitely", "undoubtedly", "clearly indicates");

    private ForbiddenVocabulary() {
    }

    /** The first forbidden word in {@code text}, if any. */
    public static Optional<String> firstViolation(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return WORDS.stream().filter(lowered::contains).findFirst();
    }

    /** The list as the prompt states it to the model. */
    public static String asPromptList() {
        return String.join(", ", WORDS);
    }
}
