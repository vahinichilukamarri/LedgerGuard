package com.ledgerguard.detection.explain.llm;

/**
 * One completion from a hosted model.
 *
 * <h2>Why an interface for a single implementation</h2>
 *
 * So the tests never make a network call. Everything above this line — the
 * retry policy, the breaker, the validator, the cache, the fallback — is
 * ordinary logic with interesting failure modes, and all of it is worth testing
 * against a client that returns exactly what a test wants, including a timeout
 * on the third call. A live API in the suite would make those tests slow, cost
 * money to run, and fail for reasons unrelated to the code.
 *
 * <p>It is the same reason Phase 7 put a fake behind the Kafka template rather
 * than testing resilience against a real broker: the thing under test is the
 * behaviour around the dependency, not the dependency.
 */
public interface GroqClient {

    /**
     * @return the model's text, never null
     * @throws GroqUnavailableException on any transport, status or shape
     *                                  problem. The caller decides what an
     *                                  absent narrative means; this throws
     *                                  rather than returning empty because
     *                                  "no text" is not a completion
     */
    String complete(String systemPrompt, String userPrompt);
}
