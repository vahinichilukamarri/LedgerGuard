package com.ledgerguard.detection.explain.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stops calling a provider that has stopped answering.
 *
 * <h2>Why a breaker and not just a timeout</h2>
 *
 * A timeout bounds one request. It does nothing about the hundredth request in
 * a row to a provider that is down, each of which waits the full four seconds
 * before falling back to the template it was always going to serve. The
 * breaker turns a sustained outage from "every request is four seconds slower"
 * into "every request is normal speed and reads a template", which is the
 * degradation this phase is supposed to deliver.
 *
 * <h2>Deliberately small</h2>
 *
 * Consecutive failures, one cooldown, one trial call to close again. No sliding
 * windows, no failure-rate thresholds, no half-open concurrency limits — a
 * library would bring all of those and a dependency, to protect one optional
 * call on one endpoint. The hard part of a breaker is not the state machine, it
 * is having a clock you can control in a test, and this project has injected
 * one since Phase 1.
 *
 * <p>Thread-safe by being almost stateless: a counter and a deadline, both
 * written under races that can at worst cost one extra trial call.
 */
public final class CircuitBreaker {

    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant openUntil = Instant.MIN;

    public CircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** Whether a call may be attempted now. */
    public boolean allowsCall() {
        return !Instant.now(clock).isBefore(openUntil);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil = Instant.MIN;
    }

    /**
     * A failure. Opens the breaker once they have run consecutively to the
     * threshold, and re-opens immediately on a failed trial call, because the
     * counter is not reset by the trial itself.
     */
    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntil = Instant.now(clock).plus(cooldown);
        }
    }

    /** True while calls are being skipped. For logging and tests, not for control flow. */
    public boolean isOpen() {
        return !allowsCall();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
