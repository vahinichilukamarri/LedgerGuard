package com.ledgerguard.detection.explain.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Groq's OpenAI-compatible chat completions endpoint, over {@code RestClient}.
 *
 * <h2>No new dependency</h2>
 *
 * {@code RestClient} and Jackson both arrive with {@code
 * spring-boot-starter-web}, which this project has had since Phase 1, and
 * Groq's API is OpenAI-shaped JSON over HTTPS. A vendor SDK would add a
 * dependency, a second HTTP stack and its own retry behaviour underneath the
 * one this class is supposed to own, to save writing two request records.
 *
 * <h2>Timeouts are the whole point</h2>
 *
 * This sits behind a read endpoint. The default HTTP client waits forever, and
 * a detection API that hangs because a model provider is slow has traded a
 * useful degradation for an outage. Connect and read timeouts are both set
 * short and both configurable, and exceeding them is an ordinary retryable
 * failure rather than an exception anybody upstream has to think about.
 *
 * <h2>The key</h2>
 *
 * Read from configuration, sent in an {@code Authorization} header, and never
 * logged — not on success, not in an error message, not at debug. The error
 * paths below quote status codes and reason phrases only.
 */
public class HttpGroqClient implements GroqClient {

    private final GroqProperties properties;
    private final RestClient http;

    public HttpGroqClient(GroqProperties properties) {
        this(properties, defaultRestClient(properties));
    }

    /** For tests that want a {@code MockRestServiceServer} behind a real client. */
    public HttpGroqClient(GroqProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    private static RestClient defaultRestClient(GroqProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMs());
        factory.setReadTimeout(properties.timeoutMs());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        ChatRequest request = new ChatRequest(
                properties.model(),
                properties.temperature(),
                properties.maxTokens(),
                List.of(new Message("system", systemPrompt), new Message("user", userPrompt)));

        ChatResponse response;
        try {
            response = http.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((ignored, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw statusFailure(status);
                        }
                        return clientResponse.bodyTo(ChatResponse.class);
                    });
        } catch (ResourceAccessException e) {
            // Connect timeout, read timeout, DNS, connection reset. All of them
            // are "try again shortly" rather than "this request is wrong".
            throw new GroqUnavailableException("groq transport failure: " + e.getMessage(), true, e);
        }

        return contentOf(response);
    }

    /**
     * 429 and 5xx are worth another attempt; 401, 403 and a malformed request
     * are not, and pretending otherwise just spends the retry budget arriving
     * at the same answer more slowly.
     */
    private static GroqUnavailableException statusFailure(HttpStatusCode status) {
        boolean retryable = status.value() == 429 || status.is5xxServerError();
        return new GroqUnavailableException("groq returned HTTP " + status.value(), retryable);
    }

    private static String contentOf(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new GroqUnavailableException("groq returned no choices", true);
        }
        Message message = response.choices().get(0).message();
        if (message == null || message.content() == null || message.content().isBlank()) {
            throw new GroqUnavailableException("groq returned an empty completion", true);
        }
        return message.content().strip();
    }

    // ------------------------------------------------------------ wire shapes

    record ChatRequest(
            String model,
            double temperature,
            @JsonProperty("max_tokens") int maxTokens,
            List<Message> messages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }

    /**
     * Unknown fields are ignored rather than rejected. A provider adding a
     * field to its response is a normal event, and failing a narrative over one
     * would turn someone else's feature release into our outage.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }
}
