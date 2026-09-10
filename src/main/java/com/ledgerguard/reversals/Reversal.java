package com.ledgerguard.reversals;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Records that one transaction was reversed by another.
 *
 * <p>Holds no money and no amount: the amounts are in the postings of the
 * reversal transaction, which exactly negate the postings of the original.
 *
 * <p>The database enforces the reverse-only-once rule through a UNIQUE
 * constraint on {@code original_transaction_id}, so two concurrent attempts
 * cannot both succeed even if both pass the service check.
 */
@Entity
@Table(name = "reversals")
public class Reversal {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "original_transaction_id", nullable = false, updatable = false, unique = true)
    private UUID originalTransactionId;

    @Column(name = "reversal_transaction_id", nullable = false, updatable = false, unique = true)
    private UUID reversalTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected Reversal() {
    }

    private Reversal(UUID id, UUID originalTransactionId, UUID reversalTransactionId, Instant createdAt) {
        this.id = id;
        this.originalTransactionId = originalTransactionId;
        this.reversalTransactionId = reversalTransactionId;
        this.createdAt = createdAt;
    }

    public static Reversal create(UUID originalTransactionId, UUID reversalTransactionId, Instant createdAt) {
        Objects.requireNonNull(originalTransactionId, "originalTransactionId");
        Objects.requireNonNull(reversalTransactionId, "reversalTransactionId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (originalTransactionId.equals(reversalTransactionId)) {
            throw new IllegalArgumentException("a transaction cannot reverse itself: " + originalTransactionId);
        }
        return new Reversal(UUID.randomUUID(), originalTransactionId, reversalTransactionId, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOriginalTransactionId() {
        return originalTransactionId;
    }

    public UUID getReversalTransactionId() {
        return reversalTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
