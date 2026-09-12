package com.ledgerguard.detection.explain.llm;

import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.explain.ForbiddenVocabulary;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.FeatureVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a generated narrative against the evidence it was supposed to restate.
 *
 * <h2>Why a prompt is not enough</h2>
 *
 * Every rule here is also in the system message. That is not duplication to be
 * tidied away: a prompt is a request and this is a guarantee, and the
 * difference shows up precisely on the inputs where a model improvises — the
 * thin-evidence account, the state it has seen less often, the sentence that
 * reads better with one more number in it. Asking nicely reduces how often that
 * happens. Only checking afterwards decides whether it reaches a reviewer.
 *
 * <h2>Five layers, cheapest first</h2>
 *
 * <ol>
 *   <li><b>Structure</b> — is this a plain-text paragraph of about the right
 *       size, or a chat reply with a preamble and bullet points?</li>
 *   <li><b>Vocabulary</b> — Phase 10's forbidden words, unchanged.</li>
 *   <li><b>Identifiers</b> — every signal or feature named must be one this
 *       account's evidence mentioned, and anything merely <em>shaped</em> like
 *       one of our identifiers must actually exist.</li>
 *   <li><b>Numbers</b> — every number must be a truthful rendering of something
 *       in the evidence.</li>
 *   <li><b>State</b> — the prose must not contradict the agreement state it was
 *       given.</li>
 * </ol>
 *
 * <p>All five run; none short-circuits. See {@link ValidationResult}.
 *
 * <h2>What it cannot catch</h2>
 *
 * Stated plainly because the guarantee is narrower than it looks. This checks
 * that every <em>name</em> and every <em>number</em> is grounded and that the
 * prose does not contradict its state. It cannot catch a narrative that
 * assembles grounded parts into a misleading whole — attributing the right
 * number to the wrong signal, or implying a causal story the evidence does not
 * support — because judging that requires understanding the sentence, and
 * anything capable of understanding it would need validating in turn. The
 * template fallback exists partly because that residue never goes to zero.
 */
public class NarrativeValidator {

    /** Below this it is not the three-to-five sentences the prompt asked for. */
    private static final int MINIMUM_LENGTH = 80;

    /** Above this a model has started explaining its reasoning or repeating itself. */
    private static final int MAXIMUM_LENGTH = 2000;

    private static final List<String> PREAMBLES = List.of(
            "here is", "here's", "sure,", "of course", "as requested",
            "summary:", "narrative:", "as an ai", "i have", "i've written");

    /** Markdown this prose should never contain, having been asked for plain text. */
    private static final Pattern MARKUP = Pattern.compile(
            "```|^\\s*#{1,6}\\s|^\\s*[-*+]\\s|\\*\\*", Pattern.MULTILINE);

    /** {@code amount_outlier} and friends. */
    private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

    /** {@code log10SecondsSinceLastPayment} and friends. */
    private static final Pattern CAMEL_CASE = Pattern.compile("\\b[a-z][a-z0-9]*(?:[A-Z][a-zA-Z0-9]*)+\\b");

    /**
     * Facts about the scales rather than about the account: the middle of the
     * isolation distribution and the two elevation conventions. Phase 10's own
     * templates quote all three, they are the same for every account, and
     * treating them as ungrounded would reject correct prose for describing the
     * scale it is using.
     */
    private static final List<Double> SCALE_CONSTANTS = List.of(
            0.5, Agreement.ML_ELEVATED, Agreement.STATISTICAL_ELEVATED,
            (double) Signal.values().length);

    private static final Set<String> KNOWN_IDENTIFIERS = knownIdentifiers();

    private static Set<String> knownIdentifiers() {
        Set<String> known = new LinkedHashSet<>();
        Arrays.stream(Signal.values()).map(Signal::wireName).forEach(known::add);
        known.addAll(Arrays.asList(FeatureVector.NAMES));
        return known;
    }

    public ValidationResult validate(String narrative, NarrativeEvidence evidence) {
        List<ValidationResult.Failure> failures = new ArrayList<>();

        if (narrative == null || narrative.isBlank()) {
            return ValidationResult.rejected(
                    List.of(new ValidationResult.Failure(ValidationResult.Reason.EMPTY, "no text")));
        }

        String text = narrative.strip();
        checkStructure(text, failures);
        checkVocabulary(text, failures);
        checkIdentifiers(text, evidence, failures);
        checkNumbers(text, evidence, failures);
        checkState(text, evidence, failures);

        return failures.isEmpty() ? ValidationResult.ok() : ValidationResult.rejected(failures);
    }

    // ----------------------------------------------------------- 1. structure

    private static void checkStructure(String text, List<ValidationResult.Failure> failures) {
        if (text.length() < MINIMUM_LENGTH) {
            failures.add(new ValidationResult.Failure(
                    ValidationResult.Reason.TOO_SHORT, text.length() + " chars"));
        }
        if (text.length() > MAXIMUM_LENGTH) {
            failures.add(new ValidationResult.Failure(
                    ValidationResult.Reason.TOO_LONG, text.length() + " chars"));
        }
        if (MARKUP.matcher(text).find()) {
            failures.add(new ValidationResult.Failure(
                    ValidationResult.Reason.MARKUP, "markdown in a plain-text narrative"));
        }

        // Only the opening matters: a preamble is a framing sentence, and the
        // same words mid-paragraph are ordinary English.
        String opening = text.substring(0, Math.min(text.length(), 40)).toLowerCase(Locale.ROOT);
        PREAMBLES.stream()
                .filter(opening::startsWith)
                .findFirst()
                .ifPresent(preamble -> failures.add(new ValidationResult.Failure(
                        ValidationResult.Reason.PREAMBLE, "starts with \"" + preamble + "\"")));
    }

    // ---------------------------------------------------------- 2. vocabulary

    private static void checkVocabulary(String text, List<ValidationResult.Failure> failures) {
        ForbiddenVocabulary.firstViolation(text).ifPresent(word ->
                failures.add(new ValidationResult.Failure(
                        ValidationResult.Reason.FORBIDDEN_WORD, word)));
    }

    // --------------------------------------------------------- 3. identifiers

    private static void checkIdentifiers(String text, NarrativeEvidence evidence,
                                         List<ValidationResult.Failure> failures) {
        Set<String> allowed = evidence.mentionableIdentifiers();

        for (String identifier : identifiersIn(text)) {
            if (allowed.contains(identifier)) {
                continue;
            }
            // A real name this account's evidence did not carry, versus a name
            // that exists nowhere. Both mislead a reader identically, but the
            // second says something worse about the model, so they are counted
            // apart.
            ValidationResult.Reason reason = KNOWN_IDENTIFIERS.contains(identifier)
                    ? ValidationResult.Reason.UNGROUNDED_IDENTIFIER
                    : ValidationResult.Reason.FABRICATED_IDENTIFIER;
            failures.add(new ValidationResult.Failure(reason, identifier));
        }
    }

    /** Every token shaped like one of this system's identifiers. */
    static Set<String> identifiersIn(String text) {
        Set<String> found = new LinkedHashSet<>();
        collect(SNAKE_CASE.matcher(text), found);
        collect(CAMEL_CASE.matcher(text), found);
        return found;
    }

    private static void collect(Matcher matcher, Set<String> into) {
        while (matcher.find()) {
            into.add(matcher.group());
        }
    }

    // -------------------------------------------------------------- 4. numbers

    private static void checkNumbers(String text, NarrativeEvidence evidence,
                                     List<ValidationResult.Failure> failures) {
        List<Double> allowed = new ArrayList<>(evidence.mentionableNumbers());
        allowed.addAll(SCALE_CONSTANTS);

        for (NumericGrounding.Stated stated : NumericGrounding.statedIn(maskIdentifiers(text))) {
            if (!NumericGrounding.isGrounded(stated, allowed)) {
                failures.add(new ValidationResult.Failure(
                        ValidationResult.Reason.UNGROUNDED_NUMBER, stated.token()));
            }
        }
    }

    /**
     * Blank out identifiers before looking for numbers.
     *
     * <p>Otherwise {@code log10SecondsSinceLastPayment} contributes a 10 that no
     * evidence value explains, and every narrative naming that feature — the
     * correct ones included — is rejected for a digit inside a word.
     */
    static String maskIdentifiers(String text) {
        String masked = SNAKE_CASE.matcher(text).replaceAll("_");
        return CAMEL_CASE.matcher(masked).replaceAll("_");
    }

    // ---------------------------------------------------------------- 5. state

    /**
     * Contradictions of the agreement state.
     *
     * <p>Narrow by design. Each entry is a phrase that is simply false in the
     * state it is listed against, not a phrase that merely sits oddly with it —
     * a validator that guessed at tone would reject good prose, and the cost of
     * a false rejection is a needlessly worse narrative on every request that
     * hits it.
     */
    private static void checkState(String text, NarrativeEvidence evidence,
                                   List<ValidationResult.Failure> failures) {
        if (evidence.agreement() == null) {
            return;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        Agreement agreement = Agreement.valueOf(evidence.agreement());

        contradiction(lowered, agreement != Agreement.BOTH_ELEVATED,
                List.of("both layers are elevated", "both scores are elevated",
                        "both layers agree that", "the two layers agree"),
                failures);

        contradiction(lowered, agreement != Agreement.BOTH_QUIET,
                List.of("neither layer is elevated", "neither score is elevated"),
                failures);

        contradiction(lowered, agreement != Agreement.STATISTICAL_ONLY
                        && agreement != Agreement.BOTH_ELEVATED,
                List.of("the statistical layer is elevated", "the composite is elevated"),
                failures);

        contradiction(lowered, agreement != Agreement.ML_ONLY
                        && agreement != Agreement.BOTH_ELEVATED,
                List.of("the model is elevated", "the isolation score is elevated"),
                failures);

        contradiction(lowered, !evidence.corroborated(),
                List.of("the layers corroborate", "corroborate each other",
                        "the two layers corroborate"),
                failures);
    }

    private static void contradiction(String lowered, boolean forbidden, List<String> phrases,
                                      List<ValidationResult.Failure> failures) {
        if (!forbidden) {
            return;
        }
        phrases.stream()
                .filter(lowered::contains)
                .findFirst()
                .ifPresent(phrase -> failures.add(new ValidationResult.Failure(
                        ValidationResult.Reason.STATE_CONTRADICTION, phrase)));
    }
}
