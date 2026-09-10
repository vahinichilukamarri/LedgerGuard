package com.ledgerguard.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A record of an answer this API has already given.
 *
 * <p><b>This is not a ledger record</b>, and it is the one entity here that is
 * allowed to change after insert. The row is written before the work begins so
 * that a competing request collides with it immediately, and the response is
 * recorded onto it once the work finishes. Both happen inside one transaction,
 * so a row with no response never becomes visible to anyone.
 *
 * <p>Postings, refunds and reversals stay immutable. Nothing here weakens that.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "endpoint", nullable = false, updatable = false, length = 200)
    private String endpoint;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** For Hibernate only. */
    protected IdempotencyKey() {
    }

    private IdempotencyKey(UUID id, String idempotencyKey, String endpoint, String requestFingerprint,
                           Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static IdempotencyKey start(String idempotencyKey, String endpoint, String requestFingerprint,
                                       Instant createdAt, Instant expiresAt) {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("request fingerprint must be 64 hex characters");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        return new IdempotencyKey(UUID.randomUUID(), idempotencyKey.trim(), endpoint,
                requestFingerprint, createdAt, expiresAt);
    }

    /** Record the answer that was given. Called once, before the transaction commits. */
    public void recordResponse(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    public boolean matchesFingerprint(String candidate) {
        return requestFingerprint.equals(candidate);
    }

    public boolean hasResponse() {
        return responseStatus != null;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
