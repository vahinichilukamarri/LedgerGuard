package com.ledgerguard.payments;

import com.ledgerguard.config.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An instruction to move money between two accounts.
 *
 * <p>The payment is the request; the {@code transactions} row it points at is
 * the resulting ledger entry. Unlike a posting, a payment is allowed to change
 * state (PENDING to POSTED) once, which is why it has a narrow mutator rather
 * than none.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, updatable = false)
    private UUID destinationAccountId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected Payment() {
    }

    private Payment(UUID id, UUID sourceAccountId, UUID destinationAccountId, long amountMinor,
                    String currency, PaymentStatus status, Instant createdAt) {
        this.id = id;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Payment pending(UUID sourceAccountId, UUID destinationAccountId, long amountMinor,
                                  String currency, Instant createdAt) {
        Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId");
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new IllegalArgumentException("source and destination accounts must differ");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("payment amount must be strictly positive, got: " + amountMinor);
        }
        return new Payment(UUID.randomUUID(), sourceAccountId, destinationAccountId, amountMinor,
                Money.normalize(currency), PaymentStatus.PENDING, createdAt);
    }

    /** Bind this payment to the balanced transaction that settled it. */
    public void markPosted(UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("payment " + id + " is already " + status);
        }
        this.transactionId = transactionId;
        this.status = PaymentStatus.POSTED;
    }

    /**
     * Record that this payment's transaction has been reversed.
     *
     * <p>This is the payment's only route out of POSTED, and it is one-way. A
     * REVERSED payment can no longer be refunded, which is the whole point:
     * the reversal already gave the money back.
     */
    public void markReversed() {
        if (this.status != PaymentStatus.POSTED) {
            throw new IllegalStateException(
                    "payment " + id + " is " + status + " and cannot be marked reversed");
        }
        this.status = PaymentStatus.REVERSED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
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

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
