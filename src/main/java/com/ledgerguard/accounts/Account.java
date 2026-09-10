package com.ledgerguard.accounts;

import com.ledgerguard.config.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account.
 *
 * <p>Deliberately has no balance column. The balance is derived by summing this
 * account postings; see {@code PostingRepository.balanceMinorUnits}.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected Account() {
    }

    private Account(UUID id, String name, String currency, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static Account create(String name, String currency, Instant createdAt) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("account name is required");
        }
        Money.fractionDigits(currency); // rejects malformed and unknown ISO-4217 codes
        return new Account(UUID.randomUUID(), name.trim(), Money.normalize(currency), createdAt);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
