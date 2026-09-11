package com.ledgerguard.chaos;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A broker that can be made to fail in ways a real broker cannot be asked to.
 *
 * <h2>Why a fake here, when Phase 4 uses a real Kafka</h2>
 *
 * The scenarios in this package test <em>our</em> handling of the broker
 * contract, not the broker. The most important failure in that contract —
 * <b>the broker accepted the record and the acknowledgement was lost</b> — is
 * precisely the one a real broker will not perform on demand. It is also the
 * failure that produces the duplicate the whole outbox design is built to
 * tolerate, so leaving it untested would leave the central claim untested.
 *
 * <p>{@code OutboxKafkaFlowIntegrationTest} keeps the real-broker proof. This
 * class exists to reach the states that proof cannot stage.
 *
 * <h2>The three outcomes</h2>
 *
 * {@link Outcome} is the whole model, and the distinction between its first two
 * values is the point of the class:
 *
 * <ul>
 *   <li>{@code DELIVERED} — the broker has it, the producer knows. Ordinary.</li>
 *   <li>{@code DELIVERED_ACK_LOST} — <b>the broker has it and the producer does
 *       not know.</b> The publisher will treat this as a failure and leave the
 *       row unpublished, so the event is sent again next cycle and the consumer
 *       sees it twice. That is at-least-once delivery happening, not a bug.</li>
 *   <li>{@code REJECTED} — the broker never got it. The row stays unpublished
 *       and nothing was delivered, so a retry is not a duplicate.</li>
 * </ul>
 *
 * <p>{@link #deliveredPayloads()} is the broker's log: what a consumer would
 * actually have received. Scenarios feed it to the real consumers rather than
 * inventing payloads, so what is being replayed is exactly what was published.
 */
public class ChaosKafkaTemplate extends KafkaTemplate<String, String> {

    /** What the broker did with one record, from the producer's point of view. */
    public enum Outcome {
        DELIVERED,
        DELIVERED_ACK_LOST,
        REJECTED
    }

    /** One record as the broker saw it. */
    public record Delivery(String topic, String key, String payload) {
    }

    /**
     * Decides the fate of the n-th send of a scenario, 1-based.
     *
     * <p>A function of the ordinal rather than of the payload, so a scenario can
     * say "the third one fails" and mean it, regardless of what the third one
     * turns out to be.
     */
    @FunctionalInterface
    public interface SendPolicy {
        Outcome decide(int sendOrdinal, Delivery delivery);
    }

    private static final SendPolicy HEALTHY = (ordinal, delivery) -> Outcome.DELIVERED;

    private final List<Delivery> delivered = new CopyOnWriteArrayList<>();
    private final List<Delivery> attempted = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendOrdinal = new AtomicInteger();

    private volatile SendPolicy policy = HEALTHY;

    public ChaosKafkaTemplate() {
        // A producer factory that is never used: send() below never delegates to
        // super, so nothing here ever opens a connection to anything.
        super(new DefaultKafkaProducerFactory<>(Map.of()));
    }

    // ------------------------------------------------------------ arming

    /** Every send succeeds and is acknowledged. The default. */
    public void healthy() {
        policy = HEALTHY;
    }

    /** The broker is unreachable: nothing is delivered, every send fails. */
    public void unavailable() {
        policy = (ordinal, delivery) -> Outcome.REJECTED;
    }

    /**
     * The broker accepts everything, but every acknowledgement is lost.
     *
     * <p>The state the outbox pattern exists for: downstream has the events,
     * the publisher believes it has published nothing.
     */
    public void acceptEverythingButLoseEveryAck() {
        policy = (ordinal, delivery) -> Outcome.DELIVERED_ACK_LOST;
    }

    /** Healthy except for one nominated send, 1-based, which gets {@code outcome}. */
    public void failOnlySend(int ordinal, Outcome outcome) {
        policy = (n, delivery) -> n == ordinal ? outcome : Outcome.DELIVERED;
    }

    /** Full control, for scenarios whose failure pattern is generated. */
    public void policy(SendPolicy sendPolicy) {
        this.policy = sendPolicy;
    }

    /** Forget the broker log and the send counter, but not the policy. */
    public void reset() {
        delivered.clear();
        attempted.clear();
        sendOrdinal.set(0);
        policy = HEALTHY;
    }

    // ------------------------------------------------------------ the send

    @Override
    public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
        Delivery delivery = new Delivery(topic, key, data);
        int ordinal = sendOrdinal.incrementAndGet();
        attempted.add(delivery);

        Outcome outcome = policy.decide(ordinal, delivery);

        // Recorded before the failure is reported, for ACK_LOST, because that is
        // the entire distinction: the broker's state and the producer's belief
        // about it diverge here.
        if (outcome != Outcome.REJECTED) {
            delivered.add(delivery);
        }

        if (outcome == Outcome.DELIVERED) {
            return CompletableFuture.completedFuture(sendResult(delivery));
        }
        return CompletableFuture.failedFuture(new TimeoutException(
                "chaos: send %d to %s was %s".formatted(ordinal, topic, outcome)));
    }

    // ------------------------------------------------------- broker state

    /** What a consumer would have received, in publish order. */
    public List<String> deliveredPayloads() {
        return delivered.stream().map(Delivery::payload).toList();
    }

    public List<Delivery> deliveries() {
        return List.copyOf(delivered);
    }

    /** Everything the publisher tried to send, including what the broker refused. */
    public int attemptedCount() {
        return attempted.size();
    }

    public int deliveredCount() {
        return delivered.size();
    }

    private static SendResult<String, String> sendResult(Delivery delivery) {
        TopicPartition partition = new TopicPartition(delivery.topic(), 0);
        return new SendResult<>(
                new ProducerRecord<>(delivery.topic(), delivery.key(), delivery.payload()),
                new RecordMetadata(partition, 0L, 0, 0L, 0, delivery.payload().length()));
    }
}
