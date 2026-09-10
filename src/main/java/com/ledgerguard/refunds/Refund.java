package com.ledgerguard.refunds;

import com.ledgerguard.config.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A record that some or all of a payment was refunded.
 *
 * <p>This row holds no money. It records that a separate balanced transaction
 * was written in the opposite direction to the original payment. The original
 * payment and its postings are never touched, which is the immutability rule
 * from Phase 1 doing its job rather than being worked around.
 *
 * <p>Immutable for the same reasons a posting is: no setters, every column
 * mapped {@code updatable = false}. A refund is a historical fact.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected Refund() {
    }

    private Refund(UUID id, UUID paymentId, UUID transactionId, long amountMinor,
                   String currency, Instant createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.transactionId = transactionId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static Refund create(UUID paymentId, UUID transactionId, long amountMinor,
                                String currency, Instant createdAt) {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("refund amount must be strictly positive, got: " + amountMinor);
        }
        return new Refund(UUID.randomUUID(), paymentId, transactionId, amountMinor,
                Money.normalize(currency), createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
