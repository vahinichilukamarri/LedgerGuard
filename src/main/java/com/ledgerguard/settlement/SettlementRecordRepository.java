package com.ledgerguard.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Unlike the ledger repositories, this one is a full {@link JpaRepository}.
 *
 * <p>That is not an oversight. Postings, refunds and reversals are append-only
 * because we own them and can hold them to that rule. These rows model somebody
 * else's system, which restates amounts, moves statuses and withdraws records.
 * Giving this repository the write methods the others deliberately lack is what
 * lets the simulator behave like the messy external world it stands in for.
 */
public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, UUID> {

    Optional<SettlementRecord> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);

    List<SettlementRecord> findByExternalReference(String externalReference);

    void deleteByExternalReference(String externalReference);
}
