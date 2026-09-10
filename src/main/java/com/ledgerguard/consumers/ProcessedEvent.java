package com.ledgerguard.consumers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A note that one consumer has already handled one event.
 *
 * <p>This exists because delivery is at-least-once. The publisher can send the
 * same event twice — after a crash between sending and marking the row — and
 * Kafka itself can redeliver on a rebalance or a failed commit. Neither is a
 * defect to be fixed upstream; both are properties of the system that
 * consumers have to absorb.
 *
 * <p>The composite primary key {@code (consumer_name, event_id)} is what
 * absorbs them. Insert first; a key violation means this event has already been
 * handled, and the second delivery becomes a no-op.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Key.class)
public class ProcessedEvent {

    @Id
    @Column(name = "consumer_name", nullable = false, updatable = false, length = 100)
    private String consumerName;

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    /** For Hibernate only. */
    protected ProcessedEvent() {
    }

    private ProcessedEvent(String consumerName, UUID eventId, Instant processedAt) {
        this.consumerName = consumerName;
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public static ProcessedEvent of(String consumerName, UUID eventId, Instant processedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(processedAt, "processedAt");
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName is required");
        }
        return new ProcessedEvent(consumerName, eventId, processedAt);
    }

    public String getConsumerName() {
        return consumerName;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    /** Composite key holder required by JPA. */
    public static class Key implements Serializable {

        private String consumerName;
        private UUID eventId;

        public Key() {
        }

        public Key(String consumerName, UUID eventId) {
            this.consumerName = consumerName;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(consumerName, key.consumerName) && Objects.equals(eventId, key.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerName, eventId);
        }
    }
}
