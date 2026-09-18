package com.ledgerguard.reconciliation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunRepository extends Repository<ReconciliationRun, UUID> {

    ReconciliationRun save(ReconciliationRun run);

    Optional<ReconciliationRun> findById(UUID id);

    long count();

    /** Run history for the console, newest first by whatever sort the caller's {@link Pageable} specifies. */
    @Query("select r from ReconciliationRun r")
    Page<ReconciliationRun> findAllBy(Pageable pageable);
}
