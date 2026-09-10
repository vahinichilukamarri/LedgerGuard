package com.ledgerguard.reversals;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Append-only, like every other ledger record here: bare {@link Repository},
 * one insert, and reads. No update or delete path exists.
 */
public interface ReversalRepository extends Repository<Reversal, UUID> {

    Reversal save(Reversal reversal);

    boolean existsByOriginalTransactionId(UUID originalTransactionId);

    Optional<Reversal> findByOriginalTransactionId(UUID originalTransactionId);
}
