package com.ledgerguard.detection.explain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Getting text out of Groq, or not, without ever making that anyone else's
 * problem.
 *
 * <h2>What lives here and what does not</h2>
 *
 * This owns the three things that make a remote call survivable — attempts,
 * backoff and the breaker — and nothing about narratives. It does not know what
 * a prompt means, it does not validate the answer, and it has no opinion about
 * what to do without one. {@code NarrativeService} owns that. The split keeps
 * the retry policy testable with a stub that counts calls, and keeps the
 * validation tests free of any notion of HTTP.
 *
 * <h2>The retry budget is small on purpose</h2>
 *
 * Two attempts, 200 ms apart, by default. This sits on a synchronous read, so
 * every retry is latency a human is waiting through to reach a template that
 * was available immediately. Exponential backoff with jitter is the right
 * answer for a background worker draining a queue; here the right answer is to
 * give up quickly.
 *
 * <p>Non-retryable failures — a bad key, a rejected request — skip the retry
 * entirely. Trying a 401 twice is a way of being slow about an answer you
 * already have.
 *
 * <h2>The backoff sleeps, and the tests do not</h2>
 *
 * {@link Sleeper} exists so the retry test asserts on attempt counts in
 * microseconds rather than waiting out a real backoff. A test suite that sleeps
 * teaches people to skip it.
 */
public class GroqGateway {

    private static final Logger log = LoggerFactory.getLogger(GroqGateway.class);

    /** Pausing between attempts, injectable so tests need not really wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final GroqClient client;
    private final GroqProperties properties;
    private final CircuitBreaker breaker;
    private final Sleeper sleeper;

    public GroqGateway(GroqClient client, GroqProperties properties, Clock clock) {
        this(client, properties,
                new CircuitBreaker(properties.failureThreshold(),
                        Duration.ofSeconds(properties.cooldownSeconds()), clock),
                Thread::sleep);
    }

    public GroqGateway(GroqClient client, GroqProperties properties,
                       CircuitBreaker breaker, Sleeper sleeper) {
        this.client = client;
        this.properties = properties;
        this.breaker = breaker;
        this.sleeper = sleeper;
    }

    /**
     * A completion, or empty.
     *
     * <p>Empty is the only failure mode this class exposes. Everything that can
     * go wrong with a remote model — the key is missing, the provider is down,
     * the breaker is open, both attempts timed out — arrives at the caller as
     * the same absence, because the caller's response to all of them is
     * identical and a richer failure type would only invite someone to treat
     * them differently.
     */
    public Optional<String> complete(String systemPrompt, String userPrompt) {
        if (!properties.active()) {
            // Not a warning. A deployment with no key is a supported way to run
            // this system, and logging per request would make the normal case
            // look broken.
            log.debug("llm: disabled or unconfigured, narratives will come from templates");
            return Optional.empty();
        }
        if (!breaker.allowsCall()) {
            log.debug("llm: circuit open after {} consecutive failures, skipping call",
                    breaker.consecutiveFailures());
            return Optional.empty();
        }

        int attempts = Math.max(1, properties.maxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String completion = client.complete(systemPrompt, userPrompt);
                breaker.recordSuccess();
                return Optional.of(completion);
            } catch (GroqUnavailableException e) {
                breaker.recordFailure();
                boolean last = attempt == attempts;

                if (!e.isRetryable() || last) {
                    log.warn("llm: giving up after {} attempt(s), serving the template instead: {}",
                            attempt, e.getMessage());
                    return Optional.empty();
                }
                log.debug("llm: attempt {} of {} failed, retrying: {}",
                        attempt, attempts, e.getMessage());

                if (!pause()) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** @return false when the thread was interrupted and the call should be abandoned */
    private boolean pause() {
        try {
            sleeper.sleep(properties.retryBackoffMs());
            return true;
        } catch (InterruptedException e) {
            // Someone is shutting this thread down. Restore the flag and stop
            // rather than swallowing it to finish an optional narrative.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Exposed for the health of the layer, not for control flow. */
    public boolean isCircuitOpen() {
        return breaker.isOpen();
    }
}
