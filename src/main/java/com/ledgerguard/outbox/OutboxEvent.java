package com.ledgerguard.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An event waiting to be published, written in the same transaction as the
 * ledger rows it describes.
 *
 * <p>Not a ledger record. Like {@code IdempotencyKey}, it is allowed to change
 * after insert — but only in one direction: {@code publishedAt} goes from null
 * to a timestamp, once. Every other column is {@code updatable = false}, so the
 * event that gets published is exactly the event that was committed.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "topic", nullable = false, updatable = false, length = 200)
    private String topic;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    /** For Hibernate only. */
    protected OutboxEvent() {
    }

    private OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType,
                        String topic, String payload, Instant occurredAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.publishAttempts = 0;
    }

    /**
     * @param id the row id, which is also the {@code eventId} in the envelope,
     *           so a republished event keeps a stable deduplication key
     */
    public static OutboxEvent pending(UUID id, EventType type, UUID aggregateId,
                                      String payload, Instant occurredAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("outbox payload is required");
        }
        return new OutboxEvent(id, type.aggregateType(), aggregateId, type.wireName(),
                type.topic(), payload, occurredAt);
    }

    /** Called after the broker has acknowledged the send, never before. */
    public void markPublished(Instant when) {
        this.publishedAt = Objects.requireNonNull(when, "when");
    }

    public void recordAttempt() {
        this.publishAttempts++;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getPublishAttempts() {
        return publishAttempts;
    }
}
