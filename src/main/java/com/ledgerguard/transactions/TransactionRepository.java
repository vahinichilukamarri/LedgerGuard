package com.ledgerguard.transactions;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Transactions with a posting against the given account, newest first by
     * whatever sort the caller's {@link Pageable} specifies.
     *
     * <p>{@code Transaction} has no direct account reference — the link is
     * through its postings — so this joins to {@code Posting} on the shared
     * {@code transaction_id} rather than following a JPA association. {@code
     * distinct} guards against a transaction with more than one leg on the same
     * account showing up twice; today's payment flow never produces that, but
     * nothing enforces it can't.
     */
    @Query("""
            select distinct t from Transaction t
            join Posting p on p.transactionId = t.id
            where p.accountId = :accountId
            """)
    Page<Transaction> findByAccountId(@Param("accountId") UUID accountId, Pageable pageable);
}
