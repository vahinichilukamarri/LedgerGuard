package com.ledgerguard.postings;

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
 * A single leg of a transaction. Immutable once created.
 *
 * <p>Immutability is enforced three ways, deliberately redundantly:
 * <ol>
 *   <li>no setters and no mutable state exposed on this class;</li>
 *   <li>every column is mapped {@code updatable = false}, so even a reflective
 *       field change on a managed instance produces no UPDATE at flush time;</li>
 *   <li>{@link PostingRepository} declares no save or delete method at all, so
 *       no caller has an update path to reach for.</li>
 * </ol>
 *
 * <p>Amount is a count of minor units and is always strictly positive; direction
 * lives in {@link #type}, never in the sign of the amount.
 */
@Entity
@Table(name = "postings")
public class Posting {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 6)
    private PostingType type;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected Posting() {
    }

    private Posting(UUID id, UUID transactionId, UUID accountId, PostingType type,
                    long amountMinor, String currency, Instant createdAt) {
        this.id = id;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.type = type;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static Posting create(UUID transactionId, UUID accountId, PostingType type,
                                 long amountMinor, String currency, Instant createdAt) {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "posting amount must be strictly positive minor units, direction is carried by type, got: "
                            + amountMinor);
        }
        return new Posting(UUID.randomUUID(), transactionId, accountId, type,
                amountMinor, Money.normalize(currency), createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public PostingType getType() {
        return type;
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

    /** Signed contribution of this posting to its account balance, in minor units. */
    public long signedAmountMinor() {
        return type.sign() * amountMinor;
    }

    @Override
    public String toString() {
        return "Posting[%s %s %d %s account=%s txn=%s]"
                .formatted(id, type, amountMinor, currency, accountId, transactionId);
    }
}
