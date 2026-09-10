package com.ledgerguard.reconciliation;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunRepository extends Repository<ReconciliationRun, UUID> {

    ReconciliationRun save(ReconciliationRun run);

    Optional<ReconciliationRun> findById(UUID id);

    long count();
}
