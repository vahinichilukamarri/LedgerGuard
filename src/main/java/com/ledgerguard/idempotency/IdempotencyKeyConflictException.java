package com.ledgerguard.idempotency;

/**
 * Thrown when a key that has already been used is presented with a different
 * request.
 *
 * <p>Surfaced as <b>409 Conflict</b>, not 422. Every 422 in this codebase means
 * "the ledger refused this" — unbalanced postings, the refund cap, reverse-once.
 * Key reuse is not a ledger rule; it is a request conflicting with state that
 * already exists, which is what 409 is for. Keeping 422 to mean one thing is
 * worth protecting.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey, String endpoint) {
        super(("idempotency key %s has already been used on %s with a different request; "
                + "use a new key for a new request, or resend the original request unchanged to replay it")
                .formatted(idempotencyKey, endpoint));
    }
}
