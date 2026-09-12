package com.ledgerguard.detection.explain.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the LLM layer, which is always present and often inactive.
 *
 * <h2>Why the beans exist even with no API key</h2>
 *
 * It would be tidier to create nothing when the key is absent. It would also
 * mean two different object graphs, one of which — the one every test and every
 * developer checkout runs — would be the one never exercised in production.
 * Instead the graph is always the same and {@link GroqProperties#active()}
 * decides at call time, so the fallback path is not a special configuration but
 * the ordinary one with a step that declines to run.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GroqProperties.class)
public class LlmConfig {

    @Bean
    public GroqClient groqClient(GroqProperties properties) {
        return new HttpGroqClient(properties);
    }

    /** Takes the application {@link Clock}, so a test can open and close the breaker at will. */
    @Bean
    public GroqGateway groqGateway(GroqClient client, GroqProperties properties, Clock clock) {
        return new GroqGateway(client, properties, clock);
    }
}
