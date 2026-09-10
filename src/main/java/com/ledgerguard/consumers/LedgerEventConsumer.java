package com.ledgerguard.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.outbox.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal downstream consumer, here to demonstrate that replay is safe rather
 * than to do anything useful with the events.
 *
 * <h2>Why this has to deduplicate</h2>
 *
 * Delivery is at-least-once. The same event can arrive more than once because
 * the publisher crashed between sending and marking the row published, or
 * because Kafka redelivered after a rebalance or a failed offset commit. A
 * consumer that assumed exactly-once would double-count on an ordinary Tuesday.
 *
 * <p>So the first thing this does with any event is claim it: insert
 * {@code (consumer_name, event_id)} and let the composite primary key decide.
 * A key violation means someone already handled it, and the delivery becomes a
 * no-op. The claim and the work share one transaction, so a failure part-way
 * through releases the claim and the event can be retried.
 *
 * <p>The claim is made <em>before</em> the work, not after. Claiming afterwards
 * would leave a window where a crash loses the record of work that was actually
 * done, which turns at-least-once delivery into at-least-once <em>effects</em>.
 */
@Component
public class LedgerEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventConsumer.class);

    /** Part of the dedupe key, so a second consumer could process the same events independently. */
    public static final String CONSUMER_NAME = "ledger-event-logger";

    private final ProcessedEventRepository processed;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Test visibility: how many events were actually acted on, as opposed to received. */
    private final AtomicInteger handled = new AtomicInteger();
    private final AtomicInteger skippedAsDuplicate = new AtomicInteger();

    public LedgerEventConsumer(ProcessedEventRepository processed, ObjectMapper mapper, Clock clock) {
        this.processed = processed;
        this.mapper = mapper;
        this.clock = clock;
    }

    @KafkaListener(
            topics = {Topics.PAYMENTS, Topics.REFUNDS, Topics.REVERSALS},
            groupId = "${ledgerguard.kafka.consumer-group:ledgerguard-ledger-events}")
    @Transactional
    public void onEvent(String rawEvent) {
        JsonNode envelope;
        try {
            envelope = mapper.readTree(rawEvent);
        } catch (Exception e) {
            // Unparseable message: log and move on rather than blocking the
            // partition forever on something that will never parse.
            log.error("consumer: could not parse event, skipping: {}", e.toString());
            return;
        }

        UUID eventId = UUID.fromString(envelope.get("eventId").asText());
        String eventType = envelope.get("eventType").asText();

        if (!claim(eventId)) {
            skippedAsDuplicate.incrementAndGet();
            log.info("consumer: event {} ({}) already processed, skipping", eventId, eventType);
            return;
        }

        // The actual "work". A real consumer would do something here; the point
        // of this phase is that whatever it does happens exactly once.
        log.info("consumer: handling {} event {} for aggregate {}",
                eventType, eventId, envelope.get("aggregateId").asText());
        handled.incrementAndGet();
    }

    /**
     * @return true if this consumer just claimed the event, false if it had
     *         already been handled
     */
    private boolean claim(UUID eventId) {
        if (processed.existsByConsumerNameAndEventId(CONSUMER_NAME, eventId)) {
            return false;
        }
        try {
            processed.save(ProcessedEvent.of(CONSUMER_NAME, eventId, Instant.now(clock)));
            return true;
        } catch (DataIntegrityViolationException concurrentDelivery) {
            // Two partitions or two instances raced on the same event. The
            // primary key settled it; this delivery lost.
            return false;
        }
    }

    public int handledCount() {
        return handled.get();
    }

    public int skippedAsDuplicateCount() {
        return skippedAsDuplicate.get();
    }

    public void resetCounters() {
        handled.set(0);
        skippedAsDuplicate.set(0);
    }
}
