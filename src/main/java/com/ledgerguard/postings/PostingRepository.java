package com.ledgerguard.postings;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view over the postings ledger.
 *
 * <p>Note what this interface extends: bare {@link Repository}, not
 * {@code CrudRepository} or {@code JpaRepository}. That is the point. Those
 * bases would hand every caller {@code save} and {@code delete}, which for an
 * append-only table is an update path waiting to be used by accident. Postings
 * are inserted in exactly one place, inside {@code TransactionService}, via the
 * EntityManager, and only after the balance check has passed.
 *
 * <p>{@code PostingImmutabilityTest} asserts this interface stays free of
 * mutating methods.
 */
public interface PostingRepository extends Repository<Posting, UUID> {

    List<Posting> findByTransactionIdOrderByTypeAscCreatedAtAsc(UUID transactionId);

    List<Posting> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    long countByTransactionId(UUID transactionId);

    /**
     * Derived balance: sum(DEBIT) - sum(CREDIT) over every posting ever written
     * for this account and currency. There is no stored balance column that
     * could drift out of sync with this.
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
            FROM postings
            WHERE account_id = :accountId AND currency = :currency
            """, nativeQuery = true)
    long balanceMinorUnits(@Param("accountId") UUID accountId, @Param("currency") String currency);
}
