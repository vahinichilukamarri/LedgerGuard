package com.ledgerguard.detection.explain.llm;

import com.ledgerguard.chaos.TickingClock;
import com.ledgerguard.detection.explain.AccountExplanation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The orchestration: cache, gateway, validator, and the template underneath all
 * of it.
 *
 * <p>The property every test here shares is that <b>the caller always gets a
 * complete narrative</b>. A timeout, a rate limit, an open breaker, a model
 * that fabricated a number — none of them produce an error, an empty field, or
 * a degraded response. They produce the deterministic narrative this system
 * served for the whole of Phase 10, and a log line nobody reading an account
 * has to care about.
 */
class NarrativeServiceTest {

    private static final Instant EPOCH = Instant.parse("2026-09-12T10:00:00Z");

    private NarrativeService serviceFor(GroqClient client) {
        return serviceFor(client, LlmFixtures.configured());
    }

    private NarrativeService serviceFor(GroqClient client, GroqProperties properties) {
        CircuitBreaker breaker = new CircuitBreaker(properties.failureThreshold(),
                Duration.ofSeconds(properties.cooldownSeconds()), new TickingClock(EPOCH));
        GroqGateway gateway = new GroqGateway(client, properties, breaker, LlmFixtures.noSleep());
        return new NarrativeService(gateway, new NarrativeValidator(),
                new NarrativeCache(properties.cacheSize()), properties);
    }

    // --------------------------------------------------------- the good path

    @Test
    @DisplayName("a valid completion is served, and marked as the model's")
    void servesValidatedModelProse() {
        NarrativeService service = serviceFor(
                new LlmFixtures.ScriptedClient().returning(LlmFixtures.VALID_NARRATIVE));

        Narrative narrative = service.narrate(LlmFixtures.bothElevated());

        assertThat(narrative.source()).isEqualTo(NarrativeSource.LLM);
        assertThat(narrative.text()).isEqualTo(LlmFixtures.VALID_NARRATIVE);
        assertThat(service.stats().fromModel()).isEqualTo(1);
    }

    @Test
    @DisplayName("the evidence, not the ledger, is what reaches the prompt")
    void promptCarriesTheEvidence() {
        LlmFixtures.ScriptedClient client =
                new LlmFixtures.ScriptedClient().returning(LlmFixtures.VALID_NARRATIVE);

        serviceFor(client).narrate(LlmFixtures.bothElevated());

        assertThat(client.prompts()).hasSize(1);
        assertThat(client.prompts().get(0))
                .contains("amount_outlier")
                .contains("amountModifiedZ")
                .contains("BOTH_ELEVATED");
    }

    // ------------------------------------------------------------- fallback

    @Test
    @DisplayName("a fabricated number falls back to the template, silently")
    void validationFailureFallsBack() {
        AccountExplanation explanation = LlmFixtures.bothElevated();
        NarrativeService service = serviceFor(new LlmFixtures.ScriptedClient()
                .returning(LlmFixtures.VALID_NARRATIVE.replace("120 training", "999 training")));

        Narrative narrative = service.narrate(explanation);

        assertThat(narrative.source()).isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(narrative.text())
                .as("the reviewer gets the complete Phase 10 narrative, not a stub")
                .isEqualTo(explanation.summary());
        assertThat(service.stats().rejected()).isEqualTo(1);
        assertThat(service.stats().rejectionRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a rejected narrative is never cached, so a later good one can replace it")
    void rejectedNarrativesAreNotCached() {
        AccountExplanation explanation = LlmFixtures.bothElevated();
        NarrativeService service = serviceFor(new LlmFixtures.ScriptedClient()
                .returning(LlmFixtures.VALID_NARRATIVE.replace("120 training", "999 training"))
                .returning(LlmFixtures.VALID_NARRATIVE));

        assertThat(service.narrate(explanation).source()).isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(service.narrate(explanation).source()).isEqualTo(NarrativeSource.LLM);
    }

    @Test
    @DisplayName("a timeout falls back to the template")
    void transportFailureFallsBack() {
        AccountExplanation explanation = LlmFixtures.bothElevated();
        NarrativeService service = serviceFor(new LlmFixtures.ScriptedClient()
                .failing(true)
                .failing(true));

        Narrative narrative = service.narrate(explanation);

        assertThat(narrative.source()).isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(narrative.text()).isEqualTo(explanation.summary());
        assertThat(service.stats().unavailable()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rate limit falls back to the template")
    void rateLimitFallsBack() {
        NarrativeService service = serviceFor(new LlmFixtures.ScriptedClient()
                .failingWith(new GroqUnavailableException("groq returned HTTP 429", true))
                .failingWith(new GroqUnavailableException("groq returned HTTP 429", true)));

        assertThat(service.narrate(LlmFixtures.bothElevated()).source())
                .isEqualTo(NarrativeSource.TEMPLATE);
    }

    @Test
    @DisplayName("a bad key falls back to the template, on one attempt")
    void authFailureFallsBack() {
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient()
                .failingWith(new GroqUnavailableException("groq returned HTTP 401", false));

        assertThat(serviceFor(client).narrate(LlmFixtures.bothElevated()).source())
                .isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(client.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("with no key configured the template is served and nothing is called")
    void unconfiguredServesTemplate() {
        AccountExplanation explanation = LlmFixtures.bothElevated();
        NarrativeService service =
                serviceFor(LlmFixtures.neverCalled(), LlmFixtures.unconfigured());

        Narrative narrative = service.narrate(explanation);

        assertThat(narrative.source()).isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(narrative.text()).isEqualTo(explanation.summary());
        assertThat(service.stats().attempted())
                .as("an unconfigured deployment is not an attempt that failed")
                .isZero();
    }

    @Test
    @DisplayName("the caller can ask for the deterministic narrative and skip the model")
    void preferTemplateSkipsTheModel() {
        AccountExplanation explanation = LlmFixtures.bothElevated();
        NarrativeService service = serviceFor(LlmFixtures.neverCalled());

        Narrative narrative = service.narrate(explanation, true);

        assertThat(narrative.source()).isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(narrative.text()).isEqualTo(explanation.summary());
    }

    @Test
    @DisplayName("an account with no trained model is narrated on one layer, not refused")
    void noModelStillNarrates() {
        NarrativeService service = serviceFor(new LlmFixtures.ScriptedClient()
                .returning("""
                        The statistical composite is 0.63 over 1 applicable signal, driven by \
                        amount_outlier at 100% of the score. Four of the five signals could \
                        not judge for want of history, which is an absence of evidence rather \
                        than evidence of absence."""));

        assertThat(service.narrate(LlmFixtures.noModel()).source())
                .isEqualTo(NarrativeSource.LLM);
    }

    // ---------------------------------------------------------------- cache

    @Test
    @DisplayName("the same evidence is narrated once and served twice")
    void cacheServesRepeats() {
        AccountExplanation explanation = LlmFixtures.bothElevated();
        LlmFixtures.ScriptedClient client =
                new LlmFixtures.ScriptedClient().returning(LlmFixtures.VALID_NARRATIVE);
        NarrativeService service = serviceFor(client);

        Narrative first = service.narrate(explanation);
        Narrative second = service.narrate(explanation);

        assertThat(second.text())
                .as("a reviewer refreshing an account must not watch the prose rewrite itself")
                .isEqualTo(first.text());
        assertThat(second.source()).isEqualTo(NarrativeSource.LLM);
        assertThat(client.calls()).isEqualTo(1);
        assertThat(service.stats().fromCache()).isEqualTo(1);
        assertThat(service.stats().attempted()).isEqualTo(1);
    }

    @Test
    @DisplayName("different evidence is narrated separately")
    void cacheDoesNotCrossAccounts() {
        LlmFixtures.ScriptedClient client = new LlmFixtures.ScriptedClient()
                .returning(LlmFixtures.VALID_NARRATIVE)
                .returning(LlmFixtures.VALID_NARRATIVE);
        NarrativeService service = serviceFor(client);

        service.narrate(LlmFixtures.bothElevated());
        service.narrate(LlmFixtures.bothQuiet());

        assertThat(client.calls()).isEqualTo(2);
        assertThat(service.stats().fromCache()).isZero();
    }

    @Test
    @DisplayName("asking for the template does not evict or populate the cache")
    void templateRequestsBypassTheCacheEntirely() {
        AccountExplanation explanation = LlmFixtures.bothElevated();
        LlmFixtures.ScriptedClient client =
                new LlmFixtures.ScriptedClient().returning(LlmFixtures.VALID_NARRATIVE);
        NarrativeService service = serviceFor(client);

        service.narrate(explanation, true);
        service.narrate(explanation, true);

        assertThat(client.calls()).isZero();
        assertThat(service.narrate(explanation).source()).isEqualTo(NarrativeSource.LLM);
        assertThat(client.calls()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- stats

    @Test
    @DisplayName("the rejection rate counts judged completions, not requests")
    void rejectionRateIgnoresUnavailability() {
        NarrativeService service = serviceFor(new LlmFixtures.ScriptedClient()
                .returning(LlmFixtures.VALID_NARRATIVE)
                .returning(LlmFixtures.VALID_NARRATIVE.replace("0.9 against", "0.4 against")));

        service.narrate(LlmFixtures.bothElevated());
        service.narrate(LlmFixtures.bothQuiet());

        NarrativeService.Stats stats = service.stats();
        assertThat(stats.fromModel()).isEqualTo(1);
        assertThat(stats.rejected()).isEqualTo(1);
        assertThat(stats.rejectionRate())
                .as("a provider outage says nothing about whether the model writes well")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("with nothing judged yet the rejection rate is zero rather than undefined")
    void rejectionRateWithoutData() {
        assertThat(serviceFor(LlmFixtures.neverCalled(), LlmFixtures.unconfigured())
                .stats().rejectionRate()).isZero();
    }
}
