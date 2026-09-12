package com.ledgerguard.detection.explain.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything configurable about the LLM narrative layer.
 *
 * <h2>The key is never in the repository</h2>
 *
 * {@link #apiKey()} defaults to empty and is supplied by the
 * {@code LEDGERGUARD_GROQ_API_KEY} environment variable, the same pattern the
 * database and Kafka settings have used since Phase 1. An empty key is not an
 * error: it means the LLM layer is off and every narrative comes from Phase
 * 10's templates, which is a fully supported way to run this system and the way
 * the test suite runs it.
 *
 * <h2>The model id is configuration, not code</h2>
 *
 * Hosted model catalogues change under you — names are deprecated, weights are
 * updated behind a stable id, and a default hardcoded in a class would rot
 * silently. The chosen default is {@code llama-3.1-8b-instant}: this task is
 * constrained rewriting rather than reasoning, so the cheapest and fastest
 * model is the right starting point, and the validator's rejection rate is the
 * measurement that says whether that was wrong. See LLM_EXPLANATION_REPORT.md.
 *
 * @param timeoutMs      read timeout. Short on purpose: this sits on a read
 *                       endpoint, and a slow narrative is worth less than a
 *                       fast template
 * @param maxAttempts    total attempts including the first. Two, not more: a
 *                       detection endpoint that retries three times against a
 *                       struggling provider has turned one slow request into a
 *                       much slower one
 * @param failureThreshold consecutive failures before the breaker opens
 * @param cooldownSeconds how long it stays open before letting one call through
 * @param cacheSize      narratives held in memory, keyed by evidence hash
 */
@ConfigurationProperties(prefix = "ledgerguard.explanation.llm")
public record GroqProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("https://api.groq.com/openai/v1") String baseUrl,
        @DefaultValue("llama-3.1-8b-instant") String model,
        @DefaultValue("0.0") double temperature,
        @DefaultValue("400") int maxTokens,
        @DefaultValue("1500") int connectTimeoutMs,
        @DefaultValue("4000") int timeoutMs,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("200") long retryBackoffMs,
        @DefaultValue("3") int failureThreshold,
        @DefaultValue("60") long cooldownSeconds,
        @DefaultValue("500") int cacheSize) {

    /**
     * Whether to call Groq at all.
     *
     * <p>Both conditions, because a key-less deployment with {@code enabled:
     * true} is the normal case rather than a misconfiguration: it is what every
     * developer checkout and every test run looks like.
     */
    public boolean active() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
