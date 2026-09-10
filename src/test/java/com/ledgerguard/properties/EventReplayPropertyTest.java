package com.ledgerguard.properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.consumers.LedgerEventConsumer;
import com.ledgerguard.outbox.EventEnvelope;
import com.ledgerguard.support.PropertyLedger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-side replay: at-least-once delivery, exactly-once effect.
 *
 * <p>Delivery is at-least-once by design, so the same event can and will arrive
 * more than once — after a publisher crash between send and mark, or after a
 * Kafka rebalance. {@code OutboxKafkaFlowIntegrationTest} proves the consumer
 * deduplicates one redelivery through a real broker. These properties drop the
 * broker and vary what the broker was only a delivery mechanism for: how many
 * copies arrive, in what order, and interleaved with which other events.
 *
 * <p>Dropping Kafka is the right trade here. The property under test is a
 * property of the {@code processed_events} composite primary key, which is a
 * database fact; adding a broker would multiply the cost per try without
 * strengthening the assertion.
 *
 * <h2>Tries</h2>
 *
 * 100 per property. Each try writes and reads {@code processed_events} several
 * times through a real PostgreSQL.
 */
@Tag("property")
class EventReplayPropertyTest {

    /**
     * However many times one event is delivered, it is handled once and claimed
     * once. Every later delivery is counted as a duplicate and does nothing.
     */
    @Property(tries = 100)
    void redeliveringOneEventHandlesItExactlyOnce(@ForAll("eventTypes") String eventType,
                                                  @ForAll @IntRange(min = 1, max = 8) int deliveries,
                                                  @ForAll @LongRange(min = 1, max = 1_000_000) long amountMinor) {
        LedgerEventConsumer consumer = PropertyLedger.bean(LedgerEventConsumer.class);
        UUID eventId = UUID.randomUUID();
        String message = envelope(eventId, eventType, amountMinor);

        consumer.resetCounters();
        for (int i = 0; i < deliveries; i++) {
            consumer.onEvent(message);

            assertThat(PropertyLedger.processedEventCount(eventId))
                    .as("delivery %d of %d must leave exactly one claim", i + 1, deliveries)
                    .isEqualTo(1L);
        }

        assertThat(consumer.handledCount())
                .as("%d deliveries of one event must be handled once", deliveries)
                .isEqualTo(1);
        assertThat(consumer.skippedAsDuplicateCount()).isEqualTo(deliveries - 1);
    }

    /**
     * Distinct events are each handled once, and redelivering any of them —
     * shuffled in among the others — never suppresses a genuine one.
     *
     * <p>The shuffle is the point. Deduplication keyed on "the last event I saw"
     * would pass a test that redelivered an event immediately and fail here.
     */
    @Property(tries = 100)
    void interleavedRedeliveriesNeverSuppressAGenuineEvent(
            @ForAll("eventTypes") String eventType,
            @ForAll @IntRange(min = 1, max = 5) int distinctEvents,
            @ForAll @IntRange(min = 1, max = 3) int copiesEach,
            @ForAll @IntRange(min = 0, max = 10_000) int shuffleSeed) {

        LedgerEventConsumer consumer = PropertyLedger.bean(LedgerEventConsumer.class);

        List<UUID> eventIds = new ArrayList<>();
        List<String> deliveries = new ArrayList<>();
        for (int i = 0; i < distinctEvents; i++) {
            UUID eventId = UUID.randomUUID();
            eventIds.add(eventId);
            String message = envelope(eventId, eventType, 100L + i);
            for (int copy = 0; copy < copiesEach; copy++) {
                deliveries.add(message);
            }
        }
        Collections.shuffle(deliveries, new Random(shuffleSeed));

        consumer.resetCounters();
        deliveries.forEach(consumer::onEvent);

        assertThat(consumer.handledCount())
                .as("each distinct event is handled exactly once, whatever the arrival order")
                .isEqualTo(distinctEvents);
        assertThat(consumer.skippedAsDuplicateCount())
                .isEqualTo(distinctEvents * copiesEach - distinctEvents);

        for (UUID eventId : eventIds) {
            assertThat(PropertyLedger.processedEventCount(eventId)).isEqualTo(1L);
        }
    }

    /**
     * A replayed event never changes financial state. Nothing in the consumer
     * writes to the ledger, and this is the property that says so out loud —
     * the ledger is read before and after and must be identical.
     */
    @Property(tries = 100)
    void replayingAProcessedEventNeverChangesTheLedger(@ForAll("eventTypes") String eventType,
                                                       @ForAll @IntRange(min = 2, max = 6) int deliveries) {
        LedgerEventConsumer consumer = PropertyLedger.bean(LedgerEventConsumer.class);

        long postingsBefore = PropertyLedger.postingCount();
        long transactionsBefore = PropertyLedger.transactionCount();
        long paymentsBefore = PropertyLedger.paymentCount();
        long ledgerNetBefore = PropertyLedger.ledgerNetMinorUnits();

        String message = envelope(UUID.randomUUID(), eventType, 4_242L);
        for (int i = 0; i < deliveries; i++) {
            consumer.onEvent(message);
        }

        assertThat(PropertyLedger.postingCount()).isEqualTo(postingsBefore);
        assertThat(PropertyLedger.transactionCount()).isEqualTo(transactionsBefore);
        assertThat(PropertyLedger.paymentCount()).isEqualTo(paymentsBefore);
        assertThat(PropertyLedger.ledgerNetMinorUnits()).isEqualTo(ledgerNetBefore);
    }

    /**
     * A message the consumer cannot parse is skipped rather than retried
     * forever, and leaves no claim behind. Blocking a partition on something
     * that will never parse is worse than dropping it.
     */
    @Property(tries = 100)
    void unparseableMessagesAreSkippedWithoutClaimingAnything(@ForAll("garbage") String garbage) {
        LedgerEventConsumer consumer = PropertyLedger.bean(LedgerEventConsumer.class);

        consumer.resetCounters();
        consumer.onEvent(garbage);

        assertThat(consumer.handledCount()).isZero();
        assertThat(consumer.skippedAsDuplicateCount()).isZero();
    }

    // ------------------------------------------------------------- generators

    @Provide
    Arbitrary<String> eventTypes() {
        return Arbitraries.of("PAYMENT_POSTED", "PAYMENT_REFUNDED", "TRANSACTION_REVERSED");
    }

    @Provide
    Arbitrary<String> garbage() {
        // Only genuinely unparseable text. JSON that parses but is not an
        // envelope (a bare array, a JSON null) is a different question, and one
        // the consumer makes no documented promise about — asserting a
        // behaviour it never claimed would be inventing a contract rather than
        // testing one.
        return Arbitraries.of("not json at all", "{", "{\"eventId\":", "}{", "{\"a\": }", "<xml/>");
    }

    // ---------------------------------------------------------------- helpers

    /** The real wire envelope, serialized by the application's own mapper. */
    private static String envelope(UUID eventId, String eventType, long amountMinor) {
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                eventType,
                "payment",
                UUID.randomUUID(),
                Instant.parse("2026-09-10T00:00:00Z"),
                Map.of("amountMinor", amountMinor, "currency", "USD"));
        try {
            return PropertyLedger.bean(ObjectMapper.class).writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not serialize a test envelope", e);
        }
    }
}
