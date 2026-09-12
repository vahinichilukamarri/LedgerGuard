package com.ledgerguard.validation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    boolean existsByExternalId(String externalId);

    List<Dispute> findByTransactionReference(String transactionReference);

    /**
     * Disputes that have actually been raised by {@code asOf}.
     *
     * <p>The simulator writes a dispute the moment it sees the payment, dated
     * weeks into the future, because that is when the scheme will raise it.
     * Nothing may become a label before then — a label that existed before it
     * was earned is the purest form of the leakage this phase is built to
     * avoid, and filtering here is what keeps it impossible rather than merely
     * unlikely.
     */
    @Query("SELECT d FROM Dispute d WHERE d.raisedAt <= :asOf ORDER BY d.raisedAt ASC, d.id ASC")
    List<Dispute> matured(@Param("asOf") Instant asOf);
}
