package com.ledgerguard.support;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.util.UUID;

/**
 * Test support for the Phase 3 requirement that every write carries an
 * {@code Idempotency-Key}.
 *
 * <p>Phase 1 and Phase 2 tests exercise payments, refunds and reversals without
 * caring about idempotency. Rather than thread a key through several dozen call
 * sites and obscure what those tests are actually about, this attaches a fresh
 * random key to any POST that does not already carry one.
 *
 * <p>A fresh key per request is the correct default here: each of those calls is
 * a genuinely distinct request, so each should be treated as one. Tests that
 * care about replay set the header themselves, and this leaves those alone.
 */
public final class TestIdempotency {

    private static final String HEADER = "Idempotency-Key";

    private TestIdempotency() {
    }

    /** Idempotent itself: calling this more than once on the same template adds one interceptor. */
    public static void autoKey(TestRestTemplate rest) {
        var interceptors = rest.getRestTemplate().getInterceptors();
        boolean alreadyInstalled = interceptors.stream().anyMatch(i -> i instanceof AutoKeyInterceptor);
        if (!alreadyInstalled) {
            interceptors.add(new AutoKeyInterceptor());
        }
    }

    private static final class AutoKeyInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution) throws java.io.IOException {

            if (HttpMethod.POST.equals(request.getMethod()) && !request.getHeaders().containsKey(HEADER)) {
                request.getHeaders().set(HEADER, UUID.randomUUID().toString());
            }
            return execution.execute(request, body);
        }
    }
}
