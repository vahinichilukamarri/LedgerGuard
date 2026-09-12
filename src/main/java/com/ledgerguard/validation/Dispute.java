package com.ledgerguard.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One dispute as the card scheme sees it.
 *
 * <p><b>Not a ledger record, and mutable</b>, for the same reason
 * {@code SettlementRecord} is: we do not own it. Disputes get reclassified,
 * withdrawn, and re-raised under a different reason code, and a model that
 * forbade that would be modelling a scheme that does not exist.
 *
 * <h2>Dated into the future, on purpose</h2>
 *
 * The simulator records a dispute the moment it sees the payment, with
 * {@link #raisedAt} set weeks later — because that is when a real cardholder
 * notices. Nothing converts it into a label until that date arrives.
 *
 * <p>That is not decoration. Label latency is one of the two things that make
 * fraud evaluation genuinely hard: the detector scored this account the day the
 * payment landed, the truth arrived sixty days later, and any evaluation that
 * forgets the gap is scoring the detector on information nobody had at the time.
 * Making the gap a real property of a real row means the harness cannot ignore
 * it by accident.
 */
@Entity
@Table(name = "disputes")
public class Dispute {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The scheme's own identifier. Unique, so a redelivered event cannot duplicate it. */
    @Column(name = "external_id", nullable = false, updatable = false, length = 100)
    private String externalId;

    /**
     * The scheme's echo of our transaction id. Nullable: a dispute we cannot
     * attribute produces no label rather than a guessed one.
     */
    @Column(name = "transaction_reference", length = 200)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private DisputeReason reason;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** When the disputed payment happened: what the label is a statement about. */
    @Column(name = "payment_at")
    private Instant paymentAt;

    /** When the dispute was raised: when the truth became available. */
    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Dispute() {
    }

    private Dispute(String externalId, String transactionReference, DisputeReason reason,
                    long amountMinor, String currency, Instant paymentAt, Instant raisedAt,
                    Instant createdAt) {
        this.id = UUID.randomUUID();
        this.externalId = externalId;
        this.transactionReference = transactionReference;
        this.reason = reason;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.paymentAt = paymentAt;
        this.raisedAt = raisedAt;
        this.createdAt = createdAt;
    }

    public static Dispute of(String externalId, String transactionReference, DisputeReason reason,
                             long amountMinor, String currency, Instant paymentAt,
                             Instant raisedAt, Instant createdAt) {
        return new Dispute(externalId, transactionReference, reason, amountMinor, currency,
                paymentAt, raisedAt, createdAt);
    }

    /** Whether this dispute has actually been raised yet, as of {@code asOf}. */
    public boolean isMatured(Instant asOf) {
        return !raisedAt.isAfter(asOf);
    }

    /** The gap between the behaviour and the truth about it. */
    public Duration latency() {
        return paymentAt == null ? Duration.ZERO : Duration.between(paymentAt, raisedAt);
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public DisputeReason getReason() {
        return reason;
    }

    public void setReason(DisputeReason reason) {
        this.reason = reason;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getPaymentAt() {
        return paymentAt;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public void setRaisedAt(Instant raisedAt) {
        this.raisedAt = raisedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
