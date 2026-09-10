package com.ledgerguard.refunds;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Refunds are append-only, so this interface extends bare {@link Repository}
 * and declares only the reads it needs plus a single insert. Nothing here can
 * update or delete a refund, matching {@code PostingRepository}.
 */
public interface RefundRepository extends Repository<Refund, UUID> {

    Refund save(Refund refund);

    List<Refund> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    /**
     * Cumulative refunded amount for a payment, derived by summing the refund
     * rows rather than read from a stored total on the payment. Same reasoning
     * as account balances in Phase 1: a derived figure cannot drift out of sync
     * with the rows that produced it.
     */
    @Query(value = "SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE payment_id = :paymentId",
            nativeQuery = true)
    long totalRefundedMinorUnits(@Param("paymentId") UUID paymentId);
}
