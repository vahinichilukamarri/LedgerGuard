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

    /**
     * The payment settled by this transaction, if the transaction is a
     * payment's, locked for the rest of the surrounding transaction.
     *
     * <p>Used by the reversal path, which has a transaction id rather than a
     * payment id. It takes the same lock, in the same order, as the refund path
     * above — deliberately, so a refund and a reversal racing on one payment
     * serialise against each other rather than each returning the money.
     *
     * <p>Returns empty for a transaction that is not a payment's, such as a
     * refund's or another reversal's. Those have no payment to guard.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.transactionId = :transactionId")
    Optional<Payment> findByTransactionIdForUpdate(@Param("transactionId") UUID transactionId);
}
