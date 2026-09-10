package com.ledgerguard.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an event into the outbox, inside whatever transaction the caller is
 * already running.
 *
 * <p><b>{@link Propagation#MANDATORY} is the point of this class.</b> It makes
 * recording an event outside a transaction a startup-visible error rather than
 * a subtle production bug. There is no code path that can write an outbox row
 * which is not bound to the ledger rows it describes: either both commit, or
 * neither does.
 *
 * <p>That is the whole outbox pattern in one annotation. Everything else here
 * is plumbing.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxRecorder(OutboxEventRepository outbox, ObjectMapper mapper, Clock clock) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * @param aggregateId also becomes the Kafka message key, so every event
     *                    about one payment stays ordered within one partition
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(EventType type, UUID aggregateId, Map<String, Object> payload) {
        Instant now = Instant.now(clock);

        // The row id and the envelope's eventId are the same value on purpose:
        // a republished event must carry the same deduplication key as its
        // first delivery, or consumer-side dedupe cannot work.
        UUID eventId = UUID.randomUUID();

        EventEnvelope envelope = new EventEnvelope(
                eventId, type.wireName(), type.aggregateType(), aggregateId, now, payload);

        return outbox.save(OutboxEvent.pending(eventId, type, aggregateId, serialize(envelope), now));
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // Failing here rolls back the ledger write too, which is correct:
            // an event we cannot serialize is an event we cannot deliver.
            throw new IllegalStateException("could not serialize outbox event " + envelope.eventId(), e);
        }
    }
}
