package com.ledgerguard.idempotency;

/**
 * Thrown when a write endpoint is called without an {@code Idempotency-Key}.
 *
 * <p>The header is required rather than optional on purpose. This phase exists
 * because an unprotected retry moves money twice, and an optional guard leaves
 * that failure mode in place for exactly the clients most likely to retry
 * badly.
 */
public class IdempotencyKeyRequiredException extends RuntimeException {

    public IdempotencyKeyRequiredException() {
        super("the Idempotency-Key header is required on this endpoint; "
                + "send a unique value per distinct request and reuse it when retrying");
    }
}
