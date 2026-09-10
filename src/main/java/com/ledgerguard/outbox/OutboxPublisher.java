package com.ledgerguard.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves committed events out of the database and onto Kafka.
 *
 * <p>This runs <b>after</b> the ledger transaction has committed, deliberately
 * rather than incidentally. Publishing inside the transaction would let Kafka
 * accept an event whose payment then rolled back — announcing money that never
 * moved.
 *
 * <h2>Delivery is at-least-once. It is not exactly-once.</h2>
 *
 * The sequence is: claim the row, send to Kafka, mark it published. If this
 * process dies between the send and the mark, the row is still unpublished and
 * is sent again on the next poll. <b>That duplicate is unavoidable.</b> Marking
 * before sending would trade it for a lost event, which is strictly worse for a
 * payments system: a consumer can discard a duplicate, but nobody can recover a
 * loss.
 *
 * <p>Consumers are therefore required to deduplicate on {@code eventId}. See
 * {@code LedgerEventConsumer}, which does exactly that. Nothing here pretends
 * the problem is solved on the producer side.
 *
 * <p>Multiple publisher instances are safe: rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so two instances never hold the same row.
 */
@Component
@ConditionalOnProperty(name = "ledgerguard.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;
    private final long sendTimeoutSeconds;

    public OutboxPublisher(OutboxEventRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           PlatformTransactionManager transactionManager,
                           Clock clock,
                           @Value("${ledgerguard.outbox.publisher.batch-size:100}") int batchSize,
                           @Value("${ledgerguard.outbox.publisher.send-timeout-seconds:10}") long sendTimeoutSeconds) {
        this.outbox = outbox;
        this.kafka = kafka;
        // An explicit template, not @Transactional on drainOnce(): that method is
        // called from publishPending() on the same object, and self-invocation
        // bypasses the proxy, so the annotation would silently do nothing.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${ledgerguard.outbox.publisher.poll-interval-ms:1000}")
    public void publishPending() {
        try {
            int published = drainOnce();
            if (published > 0) {
                log.info("outbox: published {} event(s)", published);
            }
        } catch (RuntimeException e) {
            // Kafka being unreachable is expected and survivable. Rows stay
            // unpublished, the next poll tries again, and the ledger is
            // unaffected either way.
            log.warn("outbox: publish cycle failed, will retry on next poll: {}", e.toString());
        }
    }

    /**
     * One poll cycle in its own transaction, so row locks are released as soon
     * as it ends.
     *
     * <p>On a send failure the batch stops rather than aborting: events already
     * confirmed by the broker keep their {@code published_at}, and the one that
     * failed is retried next cycle. Rolling the whole batch back would mean
     * republishing events Kafka has already accepted, manufacturing duplicates
     * this design is trying to keep rare.
     *
     * @return how many events the broker confirmed
     */
    public int drainOnce() {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> claimed = outbox.claimUnpublished(batchSize);
            if (claimed.isEmpty()) {
                return 0;
            }

            int published = 0;
            for (OutboxEvent event : claimed) {
                event.recordAttempt();

                if (!send(event)) {
                    break;
                }

                // Only after the broker has acknowledged. Marking first would
                // risk dropping the event entirely, the one outcome worse than
                // a duplicate.
                event.markPublished(Instant.now(clock));
                published++;
            }
            return published;
        });
    }

    private boolean send(OutboxEvent event) {
        try {
            kafka.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                    .get(sendTimeoutSeconds, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("outbox: interrupted publishing event {}", event.getId());
            return false;
        } catch (Exception e) {
            log.warn("outbox: could not publish event {} to {} (attempt {}): {}",
                    event.getId(), event.getTopic(), event.getPublishAttempts(), e.toString());
            return false;
        }
    }
}
