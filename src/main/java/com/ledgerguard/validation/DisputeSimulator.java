package com.ledgerguard.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.outbox.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stands in for a card scheme raising chargebacks.
 *
 * <h2>Why it consumes Kafka instead of reading the ledger</h2>
 *
 * The same decision Phase 5 made for the settlement simulator, and here it
 * matters more. A label source that derived itself from the data being judged
 * would be a mirror, and a detector evaluated against a mirror passes by
 * construction. This sees exactly what a scheme sees — an event saying a payment
 * happened — and applies its own rules in its own table. It has no access to
 * {@code payments}, {@code postings}, or any score.
 *
 * <p>Its own consumer group, so it and the settlement simulator both see every
 * event independently.
 *
 * <h2>Dated into the future</h2>
 *
 * A dispute is written the moment the payment is seen, with {@code raisedAt}
 * some weeks later, because that is when a cardholder notices. Nothing turns it
 * into a label until that date arrives — see {@link DisputeLabeller}. The system
 * therefore holds, at any moment, disputes that <em>will</em> be raised and are
 * not yet knowable, which is exactly the position a real fraud team is in and
 * exactly the thing an evaluation must not peek at.
 *
 * <h2>Seeded, not random</h2>
 *
 * Which payments get disputed is a deterministic function of the event id and a
 * configured seed. Phase 7's rule: a scenario that behaves differently on a
 * re-run teaches people to re-run it rather than read it. Here it also means a
 * label set can be regenerated exactly, which a validation phase cannot do
 * without.
 *
 * <h2>Off unless asked</h2>
 *
 * The dispute rate defaults to zero, so an ordinary deployment records nothing.
 * Inventing fraud labels for a live ledger would be considerably worse than
 * having none.
 */
@Component
public class DisputeSimulator {

    private static final Logger log = LoggerFactory.getLogger(DisputeSimulator.class);

    /**
     * Of the payments this simulator decides to dispute, the share it attributes
     * to fraud rather than to a commercial argument. The rest are recorded and
     * never become labels — which is the point of having them.
     */
    private static final double FRAUD_SHARE = 0.55;

    private final DisputeRepository disputes;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final double disputeRate;
    private final long seed;
    private final int minimumLagDays;
    private final int maximumLagDays;

    public DisputeSimulator(
            DisputeRepository disputes,
            ObjectMapper mapper,
            Clock clock,
            @Value("${ledgerguard.disputes.rate:0.0}") double disputeRate,
            @Value("${ledgerguard.disputes.seed:20260912}") long seed,
            @Value("${ledgerguard.disputes.minimum-lag-days:30}") int minimumLagDays,
            @Value("${ledgerguard.disputes.maximum-lag-days:90}") int maximumLagDays) {
        this.disputes = disputes;
        this.mapper = mapper;
        this.clock = clock;
        this.disputeRate = disputeRate;
        this.seed = seed;
        this.minimumLagDays = minimumLagDays;
        this.maximumLagDays = Math.max(minimumLagDays, maximumLagDays);
    }

    @KafkaListener(
            topics = Topics.PAYMENTS,
            groupId = "${ledgerguard.disputes.consumer-group:ledgerguard-dispute-simulator}")
    @Transactional
    public void onPayment(String rawEvent) {
        if (disputeRate <= 0) {
            return;
        }

        JsonNode envelope;
        try {
            envelope = mapper.readTree(rawEvent);
        } catch (Exception e) {
            log.error("dispute simulator: unparseable event, skipping: {}", e.toString());
            return;
        }

        String eventId = envelope.path("eventId").asText(null);
        JsonNode payload = envelope.path("payload");
        String transactionId = text(payload, "transactionId");
        if (eventId == null || transactionId == null) {
            return;
        }

        // Derived from the event id, so a redelivery lands on the same decision
        // and the UNIQUE constraint catches the duplicate rather than the
        // account acquiring a second chargeback it never earned.
        String externalId = "CB-" + eventId;
        if (disputes.existsByExternalId(externalId)) {
            return;
        }

        long draw = mix(seed, eventId.hashCode());
        if (fraction(draw) >= disputeRate) {
            return;
        }

        Instant paymentAt = Instant.now(clock);
        Instant raisedAt = paymentAt.plus(Duration.ofDays(lagDays(draw)));
        DisputeReason reason = fraction(mix(draw, 1)) < FRAUD_SHARE
                ? DisputeReason.FRAUDULENT
                : DisputeReason.NOT_RECEIVED;

        disputes.save(Dispute.of(externalId, transactionId, reason,
                payload.path("amountMinor").asLong(), payload.path("currency").asText("USD"),
                paymentAt, raisedAt, paymentAt));

        log.info("dispute simulator: {} dispute on transaction {}, raised {} days later",
                reason, transactionId, lagDays(draw));
    }

    private int lagDays(long draw) {
        int span = maximumLagDays - minimumLagDays + 1;
        return minimumLagDays + (int) (Math.floorMod(mix(draw, 2), span));
    }

    /** A value in {@code [0,1)} from a mixed draw. */
    private static double fraction(long draw) {
        return (draw >>> 11) / (double) (1L << 53);
    }

    /** SplitMix64 finalising mix, as Phase 9 uses for per-tree seeds. */
    private static long mix(long seed, long index) {
        long z = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
