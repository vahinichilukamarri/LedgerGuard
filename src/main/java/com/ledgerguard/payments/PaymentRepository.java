package com.ledgerguard.payments;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Load a payment and hold a write lock on its row until the surrounding
     * transaction ends.
     *
     * <p>Needed because the refund cap is a cross-row rule the database cannot
     * express as a CHECK constraint. Without the lock, two refunds arriving at
     * once could both read the same remaining balance, both find it sufficient,
     * and both commit, refunding more than was ever paid. The lock serialises
     * refunds against a single payment; refunds against different payments are
     * unaffected.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
}
