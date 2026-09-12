package com.ledgerguard.detection.explain.llm;

import com.ledgerguard.detection.explain.AccountExplanation;
import com.ledgerguard.detection.explain.ExplanationFixtures;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Accounts, evidence and stand-in clients for the LLM tests.
 *
 * <p>The explanations come from Phase 10's own fixtures rather than a second
 * set built here, so what the validator is tested against is the shape the
 * pipeline really produces. The clients are hand-written rather than mocked
 * with a framework: these tests care about <em>sequences</em> — fail, fail,
 * succeed — and a queue of scripted answers says that more plainly than
 * stubbing does.
 */
final class LlmFixtures {

    private LlmFixtures() {
    }

    /** Elevated on both scales, isolated on the amount axis. */
    static AccountExplanation bothElevated() {
        return AccountExplanation.of(
                ExplanationFixtures.statisticalScore(0.8),
                Optional.of(ExplanationFixtures.mlExplanation(0.9, "amountModifiedZ")),
                ExplanationFixtures.NO_MODEL_REASON);
    }

    /** Quiet everywhere: every signal applicable, none firing, model unremarkable. */
    static AccountExplanation bothQuiet() {
        return AccountExplanation.of(
                ExplanationFixtures.quietScore(),
                Optional.of(ExplanationFixtures.mlExplanation(0.3, "burstSurprisal")),
                ExplanationFixtures.NO_MODEL_REASON);
    }

    /** No forest has been trained, so there is one layer to narrate. */
    static AccountExplanation noModel() {
        return AccountExplanation.of(
                ExplanationFixtures.statisticalScore(0.7),
                Optional.empty(),
                ExplanationFixtures.NO_MODEL_REASON);
    }

    static NarrativeEvidence evidenceFor(AccountExplanation explanation) {
        return NarrativeEvidence.of(explanation);
    }

    /**
     * A narrative that should pass every check for {@link #bothElevated()}.
     *
     * <p>Every number in it is in that evidence: 0.63 is the composite, 1 the
     * applicable-signal count, 0.9 the isolation score, 120 the training rows,
     * and the two 100s and the 99 are shares and a percentile as percentages.
     *
     * <p>The composite was 0.8 here until Phase 13. A single applicable signal,
     * saturated, used to renormalise to a composite of 1.0 and could be dialled
     * anywhere below it; it now reaches the cube root of that signal's weight
     * and no further, which is 0.63. The fixture moved because the system did.
     */
    static final String VALID_NARRATIVE = """
            The statistical composite is 0.63 over 1 applicable signal, driven by amount_outlier \
            at 100% of the score. The model scores 0.9 against 120 training accounts and \
            isolated this account on amountModifiedZ, 100% of the isolation, sitting above the \
            population median at the 99th percentile. Both layers are elevated and they point at \
            the same behaviour.""";

    /** Properties with a key, so {@code active()} is true without touching the network. */
    static GroqProperties configured() {
        return new GroqProperties(true, "test-key-not-a-real-one",
                "http://localhost:0/v1", "test-model", 0.0, 400,
                1500, 4000, 2, 200, 3, 60, 100);
    }

    /** The shipping default: enabled, but no key, so nothing is ever called. */
    static GroqProperties unconfigured() {
        return new GroqProperties(true, "", "http://localhost:0/v1", "test-model",
                0.0, 400, 1500, 4000, 2, 200, 3, 60, 100);
    }

    static GroqProperties with(GroqProperties base, int maxAttempts, int failureThreshold) {
        return new GroqProperties(base.enabled(), base.apiKey(), base.baseUrl(), base.model(),
                base.temperature(), base.maxTokens(), base.connectTimeoutMs(), base.timeoutMs(),
                maxAttempts, base.retryBackoffMs(), failureThreshold, base.cooldownSeconds(),
                base.cacheSize());
    }

    /**
     * A client that answers from a script.
     *
     * <p>Each entry is either text to return or an exception to throw, so a
     * test can say "fail twice then succeed" in one line and then assert on how
     * many times it was actually called.
     */
    static final class ScriptedClient implements GroqClient {

        private final Deque<Object> script = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        ScriptedClient returning(String text) {
            script.add(text);
            return this;
        }

        ScriptedClient failing(boolean retryable) {
            script.add(new GroqUnavailableException("scripted failure", retryable));
            return this;
        }

        ScriptedClient failingWith(GroqUnavailableException failure) {
            script.add(failure);
            return this;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            prompts.add(userPrompt);
            Object next = script.poll();
            if (next == null) {
                throw new AssertionError("the client was called more times than the test scripted");
            }
            if (next instanceof GroqUnavailableException failure) {
                throw failure;
            }
            return (String) next;
        }

        int calls() {
            return prompts.size();
        }

        List<String> prompts() {
            return prompts;
        }
    }

    /** Never called; asserts as much if it is. */
    static GroqClient neverCalled() {
        return (system, user) -> {
            throw new AssertionError("the model must not be called on this path");
        };
    }

    /** Backoff that does not actually wait, so the retry tests stay instant. */
    static GroqGateway.Sleeper noSleep() {
        return millis -> {
        };
    }
}
