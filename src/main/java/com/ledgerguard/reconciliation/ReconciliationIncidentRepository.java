package com.ledgerguard.reconciliation;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Incidents are append-mostly: created by a run, and only ever changed by being
 * resolved. No delete path, because what went wrong is itself a record worth
 * keeping.
 */
public interface ReconciliationIncidentRepository
        extends Repository<ReconciliationIncident, UUID>, JpaSpecificationExecutor<ReconciliationIncident> {

    ReconciliationIncident save(ReconciliationIncident incident);

    Optional<ReconciliationIncident> findById(UUID id);

    List<ReconciliationIncident> findByRunIdOrderBySeverityDescCreatedAtAsc(UUID runId);

    List<ReconciliationIncident> findByTransactionId(UUID transactionId);

    /**
     * Every currently-open incident, used to avoid re-reporting a discrepancy
     * that nobody has dealt with yet. Loaded once per run rather than queried
     * per comparison.
     */
    List<ReconciliationIncident> findByStatus(IncidentStatus status);

    long countByStatus(IncidentStatus status);

    /** Filtering for the list endpoint, built from whichever query parameters were supplied. */
    static Specification<ReconciliationIncident> matching(DiscrepancyType type, Severity severity,
                                                          IncidentStatus status, UUID transactionId) {
        return (root, query, builder) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (type != null) {
                predicates.add(builder.equal(root.get("discrepancyType"), type));
            }
            if (severity != null) {
                predicates.add(builder.equal(root.get("severity"), severity));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (transactionId != null) {
                predicates.add(builder.equal(root.get("transactionId"), transactionId));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
