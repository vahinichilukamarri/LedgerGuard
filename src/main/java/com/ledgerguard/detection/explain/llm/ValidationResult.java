package com.ledgerguard.detection.explain.llm;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What the validator found, and why it matters that it is a list.
 *
 * <p>Validation stops nothing early. A narrative that fabricates a feature
 * name probably also states a number to go with it, and knowing both is worth
 * more than knowing the first: the reasons are what say whether a model is
 * subtly ungrounded or wholesale inventing, and that difference decides whether
 * the answer is a better prompt or a bigger model.
 *
 * <p>The {@link Reason} enum exists so those counts can be aggregated across
 * many rejections rather than read one log line at a time. Rejection rate by
 * reason is the only honest measurement of model quality available here.
 */
public record ValidationResult(boolean valid, List<Failure> failures) {

    public enum Reason {

        /** Nothing, or almost nothing, came back. */
        EMPTY,

        /** Too short to be the summary that was asked for. */
        TOO_SHORT,

        /** Longer than the brief allows; usually a model that started explaining itself. */
        TOO_LONG,

        /** Markdown, headings or bullets, where plain text was specified. */
        MARKUP,

        /** "Here is a summary of…" — chat framing that is not part of the narrative. */
        PREAMBLE,

        /** A word asserting wrongdoing or certainty. */
        FORBIDDEN_WORD,

        /** A real signal or feature name that this account's evidence never mentioned. */
        UNGROUNDED_IDENTIFIER,

        /** An identifier shaped like one of ours that does not exist at all. */
        FABRICATED_IDENTIFIER,

        /** A number that is not a truthful rendering of anything in the evidence. */
        UNGROUNDED_NUMBER,

        /** Prose that contradicts the agreement state it was given. */
        STATE_CONTRADICTION
    }

    public record Failure(Reason reason, String detail) {
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult rejected(List<Failure> failures) {
        return new ValidationResult(false, List.copyOf(failures));
    }

    /** One line, for the log that records why a template was served instead. */
    public String summary() {
        return failures.stream()
                .map(failure -> failure.reason() + "(" + failure.detail() + ")")
                .collect(Collectors.joining(", "));
    }
}
