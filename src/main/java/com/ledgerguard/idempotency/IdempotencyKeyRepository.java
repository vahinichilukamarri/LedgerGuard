package com.ledgerguard.idempotency;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends Repository<IdempotencyKey, UUID> {

    IdempotencyKey save(IdempotencyKey key);

    Optional<IdempotencyKey> findByEndpointAndIdempotencyKey(String endpoint, String idempotencyKey);
}
