package com.ledgerguard.detection.explain.llm;

import com.ledgerguard.chaos.TickingClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry policy and the breaker, with no network and no waiting.
 *
 * <p>Time comes from Phase 7's {@link TickingClock} and is advanced explicitly,
 * so the cooldown test asserts on a minute passing without taking a minute. The
 * backoff is a no-op {@link GroqGateway.Sleeper} for the same reason: a suite
 * that sleeps is a suite people learn to skip.
 */
class GroqGatewayTest {

    private static final Instant EPOCH = Instant.parse("2026-09-12T10:00:00Z");

    private GroqGateway gateway(GroqClient client, GroqProperties properties, TickingClock clock) {
        CircuitBreaker breaker = new CircuitBreaker(
                properties.failureThreshold(), Duration.ofSeconds(properties.cooldownSeconds()), clock);
        return new GroqGateway(client, properties, breaker, LlmFixtures.noSleep());
    }

    @Test
    @DisplayName("a completion comes back as itself")
    void success() {
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient().returning("a narrative");

        Optional<String> result = gateway(client, LlmFixtures.configured(), new TickingClock(EPOCH))
                .complete("system", "user");

        assertThat(result).contains("a narrative");
        assertThat(client.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retryable failure is retried, within the budget")
    void retriesRetryableFailures() {
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient()
                .failing(true)
                .returning("second time lucky");

        Optional<String> result = gateway(client, LlmFixtures.configured(), new TickingClock(EPOCH))
                .complete("system", "user");

        assertThat(result).contains("second time lucky");
        assertThat(client.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("the budget is two attempts, not more")
    void retryBudgetIsBounded() {
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient()
                .failing(true)
                .failing(true);

        Optional<String> result = gateway(client, LlmFixtures.configured(), new TickingClock(EPOCH))
                .complete("system", "user");

        assertThat(result).isEmpty();
        assertThat(client.calls())
                .as("a read endpoint that retries a struggling provider three times has turned "
                        + "one slow request into a much slower one")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a non-retryable failure is not retried")
    void doesNotRetryUnretryableFailures() {
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient().failing(false);

        Optional<String> result = gateway(client, LlmFixtures.configured(), new TickingClock(EPOCH))
                .complete("system", "user");

        assertThat(result).isEmpty();
        assertThat(client.calls())
                .as("trying a bad key twice is a way of being slow about an answer you have")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("with no API key the model is never called")
    void unconfiguredNeverCalls() {
        Optional<String> result =
                gateway(LlmFixtures.neverCalled(), LlmFixtures.unconfigured(), new TickingClock(EPOCH))
                        .complete("system", "user");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("disabled by configuration, the model is never called even with a key")
    void disabledNeverCalls() {
        GroqProperties disabled = new GroqProperties(false, "a-key", "http://localhost:0/v1",
                "test-model", 0.0, 400, 1500, 4000, 2, 200, 3, 60, 100);

        assertThat(gateway(LlmFixtures.neverCalled(), disabled, new TickingClock(EPOCH))
                .complete("system", "user")).isEmpty();
    }

    // ------------------------------------------------------------- breaker

    @Test
    @DisplayName("after the failure threshold the breaker opens and calls stop")
    void breakerOpens() {
        TickingClock clock = new TickingClock(EPOCH);
        // Threshold 2, one attempt each, so two calls trip it.
        GroqProperties properties = LlmFixtures.with(LlmFixtures.configured(), 1, 2);
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient()
                .failing(true)
                .failing(true);

        GroqGateway gateway = gateway(client, properties, clock);
        gateway.complete("system", "user");
        gateway.complete("system", "user");

        assertThat(gateway.isCircuitOpen()).isTrue();

        // A third request must not reach the client at all: the script is
        // exhausted, so a call here fails the test rather than passing quietly.
        assertThat(gateway.complete("system", "user")).isEmpty();
        assertThat(client.calls())
                .as("an open breaker is what turns a provider outage from slow into instant")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("after the cooldown one trial call is allowed, and success closes the breaker")
    void breakerClosesAfterCooldown() {
        TickingClock clock = new TickingClock(EPOCH);
        GroqProperties properties = LlmFixtures.with(LlmFixtures.configured(), 1, 2);
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient()
                .failing(true)
                .failing(true)
                .returning("recovered")
                .returning("still fine");

        GroqGateway gateway = gateway(client, properties, clock);
        gateway.complete("system", "user");
        gateway.complete("system", "user");
        assertThat(gateway.isCircuitOpen()).isTrue();

        clock.advance(Duration.ofSeconds(properties.cooldownSeconds() + 1));

        assertThat(gateway.complete("system", "user")).contains("recovered");
        assertThat(gateway.isCircuitOpen()).isFalse();
        assertThat(gateway.complete("system", "user")).contains("still fine");
    }

    @Test
    @DisplayName("a failed trial call re-opens the breaker immediately")
    void failedTrialReopens() {
        TickingClock clock = new TickingClock(EPOCH);
        GroqProperties properties = LlmFixtures.with(LlmFixtures.configured(), 1, 2);
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient()
                .failing(true)
                .failing(true)
                .failing(true);

        GroqGateway gateway = gateway(client, properties, clock);
        gateway.complete("system", "user");
        gateway.complete("system", "user");
        clock.advance(Duration.ofSeconds(properties.cooldownSeconds() + 1));

        gateway.complete("system", "user");

        assertThat(gateway.isCircuitOpen())
                .as("one success closes it; one more failure must not")
                .isTrue();
        assertThat(client.calls()).isEqualTo(3);
    }

    @Test
    @DisplayName("a success resets the failure count, so failures must be consecutive")
    void successResetsTheCount() {
        TickingClock clock = new TickingClock(EPOCH);
        GroqProperties properties = LlmFixtures.with(LlmFixtures.configured(), 1, 2);
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient()
                .failing(true)
                .returning("fine")
                .failing(true);

        GroqGateway gateway = gateway(client, properties, clock);
        gateway.complete("system", "user");
        gateway.complete("system", "user");
        gateway.complete("system", "user");

        assertThat(gateway.isCircuitOpen())
                .as("two failures either side of a success are not a provider that is down")
                .isFalse();
    }
}
