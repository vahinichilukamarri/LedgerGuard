package com.ledgerguard.settlement;

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
 * One movement of money as the external processor sees it.
 *
 * <p><b>This is not a ledger record and it is deliberately mutable.</b> Every
 * other entity in this codebase that represents money is append-only, because
 * we own the ledger and can hold it to that rule. We do not own the processor.
 * Its records change: amounts get corrected, statuses move from PENDING to
 * SETTLED or FAILED, records get withdrawn. Modelling that faithfully is what
 * makes reconciliation worth running.
 *
 * <p>The fault-injection endpoint edits these rows for exactly that reason. It
 * is not cheating around immutability; it is simulating a system that has none.
 */
@Entity
@Table(name = "settlement_records")
public class SettlementRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The processor's own identifier. */
    @Column(name = "external_id", nullable = false, updatable = false, length = 100)
    private String externalId;

    /**
     * The processor's echo of our internal transaction id: the matching key.
     * Nullable on purpose — a record with no usable reference is a real
     * scenario, and classifies as UNEXPECTED_EXTERNAL_TRANSACTION.
     */
    @Column(name = "external_reference", length = 200)
    private String externalReference;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SettlementStatus status;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected SettlementRecord() {
    }

    private SettlementRecord(UUID id, String externalId, String externalReference, long amountMinor,
                             String currency, SettlementStatus status, Instant settledAt, Instant createdAt) {
        this.id = id;
        this.externalId = externalId;
        this.externalReference = externalReference;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.settledAt = settledAt;
        this.createdAt = createdAt;
    }

    public static SettlementRecord of(String externalId, String externalReference, long amountMinor,
                                      String currency, SettlementStatus status, Instant settledAt) {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(settledAt, "settledAt");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("settlement amount cannot be negative, got: " + amountMinor);
        }
        return new SettlementRecord(UUID.randomUUID(), externalId, externalReference, amountMinor,
                currency, status, settledAt, settledAt);
    }

    // --- the external system changing its mind ---

    public void restate(long newAmountMinor) {
        if (newAmountMinor < 0) {
            throw new IllegalArgumentException("settlement amount cannot be negative, got: " + newAmountMinor);
        }
        this.amountMinor = newAmountMinor;
    }

    public void moveTo(SettlementStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus");
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
