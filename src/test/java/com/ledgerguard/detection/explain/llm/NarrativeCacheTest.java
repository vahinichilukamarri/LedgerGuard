package com.ledgerguard.detection.explain.llm;

import com.ledgerguard.detection.explain.AccountExplanation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache, which is this phase's answer to determinism.
 *
 * <p>What is being pinned is what goes into the key. Too little and a
 * narrative outlives the numbers it describes; too much — {@code asOf}, the
 * account id — and two identical records pay twice to be worded differently.
 */
class NarrativeCacheTest {

    private static String keyFor(AccountExplanation explanation) {
        return keyFor(explanation, "test-model", PromptBuilder.PROMPT_VERSION);
    }

    private static String keyFor(AccountExplanation explanation, String model, String promptVersion) {
        return NarrativeCache.keyFor(model, promptVersion,
                PromptBuilder.toJson(NarrativeEvidence.of(explanation)));
    }

    @Test
    @DisplayName("the same evidence produces the same key")
    void stableKey() {
        assertThat(keyFor(LlmFixtures.bothElevated()))
                .isEqualTo(keyFor(LlmFixtures.bothElevated()));
    }

    @Test
    @DisplayName("different evidence produces a different key")
    void evidenceChangesTheKey() {
        assertThat(keyFor(LlmFixtures.bothElevated()))
                .as("prose about numbers that have moved must not be representable")
                .isNotEqualTo(keyFor(LlmFixtures.bothQuiet()));
    }

    @Test
    @DisplayName("a different model produces a different key")
    void modelChangesTheKey() {
        assertThat(keyFor(LlmFixtures.bothElevated(), "model-a", PromptBuilder.PROMPT_VERSION))
                .isNotEqualTo(keyFor(LlmFixtures.bothElevated(), "model-b",
                        PromptBuilder.PROMPT_VERSION));
    }

    @Test
    @DisplayName("a different prompt version produces a different key")
    void promptVersionChangesTheKey() {
        assertThat(keyFor(LlmFixtures.bothElevated(), "test-model", "p11-v1"))
                .as("editing the prompt without invalidating would serve old prose "
                        + "as if the new prompt had written it")
                .isNotEqualTo(keyFor(LlmFixtures.bothElevated(), "test-model", "p11-v2"));
    }

    /**
     * Two assessments of one account at different instants, with every number
     * unchanged. The narrative describes the numbers, so it should be reused.
     */
    @Test
    @DisplayName("the same numbers at a different instant reuse the same entry")
    void timeAloneDoesNotChangeTheKey() {
        AccountExplanation first = LlmFixtures.bothElevated();
        AccountExplanation later = new AccountExplanation(
                first.accountId(),
                first.asOf().plusSeconds(3600),
                first.statistical(),
                first.ml(),
                first.mlUnavailableReason(),
                first.reconciliation(),
                first.summary(),
                first.caveats());

        assertThat(keyFor(later)).isEqualTo(keyFor(first));
    }

    @Test
    @DisplayName("a stored narrative comes back, and an unknown key does not")
    void storeAndFetch() {
        NarrativeCache cache = new NarrativeCache(10);
        cache.put("key-1", "a narrative");

        assertThat(cache.get("key-1")).contains("a narrative");
        assertThat(cache.get("key-2")).isEmpty();
    }

    @Test
    @DisplayName("the cache is bounded, evicting least-recently-used first")
    void boundedAndLru() {
        NarrativeCache cache = new NarrativeCache(2);
        cache.put("a", "first");
        cache.put("b", "second");

        // Touching "a" makes "b" the least recently used.
        assertThat(cache.get("a")).contains("first");
        cache.put("c", "third");

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("a")).as("recently read, so kept").contains("first");
        assertThat(cache.get("c")).contains("third");
        assertThat(cache.get("b")).as("least recently used, so evicted").isEmpty();
    }

    @Test
    @DisplayName("a cache of zero is still a cache of one, not a crash")
    void degenerateSize() {
        NarrativeCache cache = new NarrativeCache(0);
        cache.put("a", "first");

        assertThat(cache.size()).isLessThanOrEqualTo(1);
    }
}
