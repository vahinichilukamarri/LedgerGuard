package com.ledgerguard.detection.explain.llm;

/**
 * Groq did not produce a completion.
 *
 * <h2>Retryable is a property of the failure, not of the caller's patience</h2>
 *
 * A 429 or a 503 says "the same request might work shortly". A 401 says "this
 * request will never work", and retrying it burns latency on a read endpoint to
 * arrive at the same answer. So the distinction is recorded where it is known —
 * at the point the status code is read — rather than inferred later from an
 * exception type hierarchy that would have to be kept in sync with it.
 *
 * <p>Nothing that reaches this class ever fails a request. Every path ends in a
 * template narrative; see {@code NarrativeService}.
 */
public class GroqUnavailableException extends RuntimeException {

    private final boolean retryable;

    public GroqUnavailableException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public GroqUnavailableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** Whether another attempt at the same request could plausibly succeed. */
    public boolean isRetryable() {
        return retryable;
    }
}
