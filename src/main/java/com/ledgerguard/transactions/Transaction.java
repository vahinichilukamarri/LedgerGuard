package com.ledgerguard.transactions;

import com.ledgerguard.config.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A financial event. Carries no amount of its own; the amounts live in its
 * postings, and those postings must net to zero per currency.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected Transaction() {
    }

    private Transaction(UUID id, String description, String currency, Instant createdAt) {
        this.id = id;
        this.description = description;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static Transaction create(String description, String currency, Instant createdAt) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("transaction description is required");
        }
        Money.fractionDigits(currency); // rejects malformed and unknown ISO-4217 codes
        return new Transaction(UUID.randomUUID(), description.trim(), Money.normalize(currency), createdAt);
    }

    public UUID getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
