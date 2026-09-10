package com.ledgerguard.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for what actually gets written into the outbox.
 *
 * <p>No Spring and no database. The two properties that matter here are
 * structural: the envelope's {@code eventId} must equal the row id, and amounts
 * must stay integers on the wire.
 */
class OutboxRecorderTest {

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);

    private final OutboxRecorder recorder = new OutboxRecorder(outbox, mapper, clock);

    private final UUID paymentId = UUID.randomUUID();

    private OutboxEvent record(EventType type, Map<String, Object> payload) {
        when(outbox.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        return recorder.record(type, paymentId, payload);
    }

    private JsonNode envelopeOf(OutboxEvent event) {
        try {
            return mapper.readTree(event.getPayload());
        } catch (Exception e) {
            throw new AssertionError("outbox payload was not valid JSON: " + event.getPayload(), e);
        }
    }

    @Test
    @DisplayName("the envelope eventId is the outbox row id, so republishing keeps a stable dedupe key")
    void eventIdEqualsRowId() {
        OutboxEvent event = record(EventType.PAYMENT_POSTED, Map.of("paymentId", paymentId.toString()));

        assertThat(envelopeOf(event).get("eventId").asText())
                .as("a redelivery must carry the same id its first delivery did")
                .isEqualTo(event.getId().toString());
    }

    @Test
    @DisplayName("the event is routed to the topic its type belongs to")
    void eventGoesToTheRightTopic() {
        assertThat(record(EventType.PAYMENT_POSTED, Map.of("a", 1)).getTopic())
                .isEqualTo(Topics.PAYMENTS);
        assertThat(record(EventType.PAYMENT_REFUNDED, Map.of("a", 1)).getTopic())
                .isEqualTo(Topics.REFUNDS);
        assertThat(record(EventType.TRANSACTION_REVERSED, Map.of("a", 1)).getTopic())
                .isEqualTo(Topics.REVERSALS);
    }

    @Test
    @DisplayName("a new event starts unpublished with no attempts")
    void newEventIsPending() {
        OutboxEvent event = record(EventType.PAYMENT_POSTED, Map.of("a", 1));

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPublishAttempts()).isZero();
        assertThat(event.getAggregateId()).isEqualTo(paymentId);
        assertThat(event.getAggregateType()).isEqualTo("Payment");
        assertThat(event.getEventType()).isEqualTo("PaymentPosted");
    }

    @Test
    @DisplayName("amounts stay integer minor units on the wire, never decimals")
    void amountsAreIntegerMinorUnits() {
        OutboxEvent event = record(EventType.PAYMENT_POSTED, Map.of(
                "paymentId", paymentId.toString(),
                "amountMinor", 1025L,
                "currency", "USD"));

        JsonNode amount = envelopeOf(event).get("payload").get("amountMinor");

        assertThat(amount.isIntegralNumber())
                .as("serialising 10.25 would reintroduce the precision problem at the boundary")
                .isTrue();
        assertThat(amount.asLong()).isEqualTo(1025L);
        assertThat(event.getPayload())
                .as("no decimal rendering of the amount should appear anywhere in the payload")
                .doesNotContain("10.25");
    }

    @Test
    @DisplayName("the envelope carries everything a consumer needs to act without calling back")
    void envelopeIsSelfContained() {
        OutboxEvent event = record(EventType.PAYMENT_POSTED, Map.of(
                "paymentId", paymentId.toString(),
                "amountMinor", 1025L,
                "currency", "USD"));

        JsonNode envelope = envelopeOf(event);
        java.util.List<String> fields = new java.util.ArrayList<>();
        envelope.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder(
                "eventId", "eventType", "aggregateType", "aggregateId", "occurredAt", "payload");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(paymentId.toString());
        assertThat(envelope.get("occurredAt").asText()).contains("2026-03-01");
        assertThat(envelope.get("payload").get("currency").asText()).isEqualTo("USD");
    }

    @Test
    @DisplayName("recording is only possible inside an existing transaction")
    void recordRequiresAnExistingTransaction() throws Exception {
        Method record = OutboxRecorder.class.getMethod("record", EventType.class, UUID.class, Map.class);
        var annotation = record.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(annotation).as("record() must be transactional").isNotNull();
        assertThat(annotation.propagation())
                .as("MANDATORY is what makes an orphaned outbox row impossible: "
                        + "an event cannot be written outside the transaction that produced it")
                .isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
    }

    @Test
    @DisplayName("published_at is the only thing that can change after insert")
    void onlyPublishedAtIsMutable() {
        OutboxEvent event = record(EventType.PAYMENT_POSTED, Map.of("a", 1));
        String payloadBefore = event.getPayload();

        event.markPublished(Instant.now(clock));

        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPayload())
                .as("the event that gets published must be the event that was committed")
                .isEqualTo(payloadBefore);
    }
}
