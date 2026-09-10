package com.ledgerguard.outbox;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What actually goes on the wire.
 *
 * <p>The envelope is deliberately uniform across every event family, so a
 * consumer can deduplicate and route without understanding the specific
 * payload it is holding.
 *
 * <p><b>{@code eventId} is the outbox row id.</b> That matters: a republished
 * event carries the <em>same</em> id as its first delivery, which is the only
 * reason consumer-side deduplication works. An id generated at send time would
 * make every retry look like a new event.
 *
 * <p>Amounts inside {@code payload} are integer minor units, never decimals.
 * Serialising {@code 10.25} would reintroduce the precision problem Phase 1
 * exists to prevent, at the system boundary where it is hardest to notice.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,

        /*
         * Pinned to a string rather than left to the ambient ObjectMapper. With
         * default Jackson settings an Instant serialises as a float epoch
         * (1.7723592E9); Spring Boot happens to turn that off, but this is a
         * published wire contract and it should not depend on a framework
         * default somebody could change.
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt,

        Map<String, Object> payload) {

    public EventEnvelope {
        payload = Map.copyOf(payload);
    }
}
