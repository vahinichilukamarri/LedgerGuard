package com.ledgerguard.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.outbox.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Stands in for an external payment processor.
 *
 * <h2>Why it consumes Kafka instead of reading the ledger</h2>
 *
 * This is the design decision the whole phase rests on. If the simulator
 * queried {@code transactions} and copied the amounts across, reconciliation
 * would be comparing the ledger against a mirror of itself, and it would pass
 * by construction. A comparison that cannot fail is not a comparison.
 *
 * <p>So it consumes the published event stream and nothing else — exactly what
 * a real processor gets: an instruction, arriving asynchronously, which it
 * applies under its own rules and records in its own table. It has no access
 * to, and no knowledge of, {@code transactions} or {@code postings}.
 *
 * <p>Its own consumer group, separate from {@code LedgerEventConsumer}, so both
 * see every event independently.
 *
 * <h2>Redelivery</h2>
 *
 * Delivery is at-least-once, so this listener can see the same event twice. It
 * derives {@code externalId} from the event id and relies on that column's
 * UNIQUE constraint, because a duplicate settlement record created by a
 * <em>redelivery</em> would show up as a DUPLICATE_SETTLEMENT incident that
 * describes a bug in this class rather than anything about the ledger.
 */
@Component
public class SettlementSimulator {

    private static final Logger log = LoggerFactory.getLogger(SettlementSimulator.class);

    private final SettlementRecordRepository records;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SettlementSimulator(SettlementRecordRepository records, ObjectMapper mapper, Clock clock) {
        this.records = records;
        this.mapper = mapper;
        this.clock = clock;
    }

    @KafkaListener(
            topics = {Topics.PAYMENTS, Topics.REFUNDS, Topics.REVERSALS},
            groupId = "${ledgerguard.settlement.consumer-group:ledgerguard-settlement-simulator}")
    @Transactional
    public void onLedgerEvent(String rawEvent) {
        JsonNode envelope;
        try {
            envelope = mapper.readTree(rawEvent);
        } catch (Exception e) {
            log.error("settlement simulator: unparseable event, skipping: {}", e.toString());
            return;
        }

        String eventId = envelope.get("eventId").asText();
        String eventType = envelope.get("eventType").asText();
        JsonNode payload = envelope.get("payload");

        // Stable and derived from the event, so a redelivery collides instead of
        // inventing a second settlement.
        String externalId = "SIM-" + eventId;
        if (records.existsByExternalId(externalId)) {
            log.debug("settlement simulator: already settled event {}, skipping", eventId);
            return;
        }

        String reference = settledTransactionOf(eventType, payload);
        if (reference == null) {
            log.warn("settlement simulator: no settleable transaction on {} event {}", eventType, eventId);
            return;
        }

        long amountMinor = payload.path("amountMinor").asLong();
        String currency = payload.path("currency").asText("USD");

        records.save(SettlementRecord.of(
                externalId, reference, amountMinor, currency,
                SettlementStatus.SETTLED, Instant.now(clock)));

        log.info("settlement simulator: settled {} for transaction {} ({} {})",
                eventType, reference, amountMinor, currency);
    }

    /**
     * Which internal transaction this event settles.
     *
     * <p>For payments and refunds that is the transaction the event created.
     * For a reversal the event is keyed on the transaction being reversed, but
     * the movement of money is the <em>new</em> reversal transaction, so that is
     * what gets settled.
     */
    private String settledTransactionOf(String eventType, JsonNode payload) {
        return switch (eventType) {
            case "PaymentPosted", "PaymentRefunded" -> textOrNull(payload, "transactionId");
            case "TransactionReversed" -> textOrNull(payload, "reversalTransactionId");
            default -> null;
        };
    }

    private static String textOrNull(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
