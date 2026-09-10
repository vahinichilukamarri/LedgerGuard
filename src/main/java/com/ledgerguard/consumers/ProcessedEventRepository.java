package com.ledgerguard.consumers;

import org.springframework.data.repository.Repository;

import java.util.UUID;

public interface ProcessedEventRepository extends Repository<ProcessedEvent, ProcessedEvent.Key> {

    ProcessedEvent save(ProcessedEvent processedEvent);

    boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);

    long countByEventId(UUID eventId);
}
