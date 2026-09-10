package com.ledgerguard.outbox;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends Repository<OutboxEvent, UUID> {

    OutboxEvent save(OutboxEvent event);

    Optional<OutboxEvent> findById(UUID id);

    List<OutboxEvent> findByAggregateIdOrderByOccurredAtAsc(UUID aggregateId);

    long countByPublishedAtIsNull();

    /**
     * Claim a batch of unpublished events for this publisher and nobody else.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes more than one publisher
     * instance safe. Rows another publisher is already holding are skipped
     * rather than waited on, so two instances never send the same event and
     * neither blocks the other.
     *
     * <p>Ordered by {@code occurred_at} so events are published roughly in the
     * order they happened. Per-aggregate ordering is guaranteed on the Kafka
     * side instead, by keying every message on the aggregate id.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY occurred_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("batchSize") int batchSize);
}
