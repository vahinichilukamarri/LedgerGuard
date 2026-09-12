package com.ledgerguard.detection.explain.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The HTTP layer, against a mock server rather than Groq.
 *
 * <p>No test in this suite makes a live API call. It would cost money per run,
 * fail for reasons unconnected to the code, and make the suite's result depend
 * on a third party's uptime — and the thing worth testing here is not whether
 * Groq works. It is whether a 429 is retryable and a 401 is not, whether an
 * unrecognised field in a response breaks anything, and whether the key can
 * leak into an error message.
 */
class HttpGroqClientTest {

    private static final String KEY = "gsk-test-key-that-is-not-real";

    private record Harness(HttpGroqClient client, MockRestServiceServer server) {
    }

    private static Harness harness() {
        GroqProperties properties = new GroqProperties(true, KEY, "http://groq.test/v1",
                "test-model", 0.0, 400, 1500, 4000, 2, 200, 3, 60, 100);

        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(new HttpGroqClient(properties, builder.build()), server);
    }

    private static final String COMPLETION = """
            {"id":"chat-1","object":"chat.completion","model":"test-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"  a narrative  "},
                         "finish_reason":"stop"}],
             "usage":{"total_tokens":412}}
            """;

    @Test
    @DisplayName("a completion is unwrapped and trimmed")
    void success() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://groq.test/v1/chat/completions"))
                .andRespond(withSuccess(COMPLETION, MediaType.APPLICATION_JSON));

        assertThat(harness.client().complete("system", "user")).isEqualTo("a narrative");
        harness.server().verify();
    }

    @Test
    @DisplayName("the request is the shape Groq's chat API expects")
    void requestShape() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://groq.test/v1/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.temperature").value(0.0))
                .andExpect(jsonPath("$.max_tokens").value(400))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("the system prompt"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("the user prompt"))
                .andRespond(withSuccess(COMPLETION, MediaType.APPLICATION_JSON));

        harness.client().complete("the system prompt", "the user prompt");
        harness.server().verify();
    }

    /**
     * A provider adding a field to its response is a normal event. Failing a
     * narrative over one would turn someone else's release into our outage.
     */
    @Test
    @DisplayName("fields we do not know about are ignored, not rejected")
    void unknownFieldsAreIgnored() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://groq.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"fine","role":"assistant",
                          "reasoning":"something new"},"logprobs":null}],
                         "x_groq":{"id":"req_1"},"service_tier":"on_demand"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(harness.client().complete("system", "user")).isEqualTo("fine");
    }

    // ------------------------------------------------------- failure mapping

    @Test
    @DisplayName("a rate limit is retryable")
    void rateLimitIsRetryable() {
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, true);
    }

    @Test
    @DisplayName("a server error is retryable")
    void serverErrorIsRetryable() {
        assertStatus(HttpStatus.INTERNAL_SERVER_ERROR, true);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, true);
    }

    @Test
    @DisplayName("an authentication failure is not retryable")
    void authFailureIsNotRetryable() {
        assertStatus(HttpStatus.UNAUTHORIZED, false);
        assertStatus(HttpStatus.FORBIDDEN, false);
    }

    @Test
    @DisplayName("a rejected request is not retryable")
    void badRequestIsNotRetryable() {
        assertStatus(HttpStatus.BAD_REQUEST, false);
    }

    private static void assertStatus(HttpStatus status, boolean retryable) {
        Harness harness = harness();
        harness.server().expect(requestTo("http://groq.test/v1/chat/completions"))
                .andRespond(withStatus(status));

        assertThatThrownBy(() -> harness.client().complete("system", "user"))
                .isInstanceOf(GroqUnavailableException.class)
                .hasMessageContaining(String.valueOf(status.value()))
                .extracting(thrown -> ((GroqUnavailableException) thrown).isRetryable())
                .isEqualTo(retryable);
    }

    @Test
    @DisplayName("a response with no choices is a failure, not an empty narrative")
    void noChoices() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://groq.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.client().complete("system", "user"))
                .isInstanceOf(GroqUnavailableException.class)
                .hasMessageContaining("no choices");
    }

    @Test
    @DisplayName("an empty completion is a failure, not an empty narrative")
    void emptyContent() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://groq.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"   \"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.client().complete("system", "user"))
                .isInstanceOf(GroqUnavailableException.class)
                .hasMessageContaining("empty completion");
    }

    /**
     * The one test here that is about security rather than behaviour. An error
     * message travels into logs, and a key in a log is a leaked key.
     */
    @Test
    @DisplayName("no failure message contains the API key")
    void theKeyNeverLeaksIntoAnError() {
        for (HttpStatus status : new HttpStatus[]{
                HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_REQUEST}) {

            Harness harness = harness();
            harness.server().expect(requestTo("http://groq.test/v1/chat/completions"))
                    .andRespond(withStatus(status));

            assertThatThrownBy(() -> harness.client().complete("system", "user"))
                    .hasMessageNotContaining(KEY)
                    .hasMessageNotContaining("Bearer");
        }
    }
}
