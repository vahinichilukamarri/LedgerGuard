package com.ledgerguard.validation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Reads and appends labels. There is deliberately no update or delete.
 *
 * <p>{@code JpaRepository} supplies {@code save} and {@code delete}; only the
 * first is ever called, and {@link AccountLabel} has no setters, so a label that
 * has been written cannot be altered through this interface. The schema carries
 * the same intent in its {@code updatable = false} columns.
 */
public interface AccountLabelRepository extends JpaRepository<AccountLabel, UUID> {

    List<AccountLabel> findByAccountId(UUID accountId);

    List<AccountLabel> findBySource(LabelSource source);

    boolean existsByAccountIdAndSource(UUID accountId, LabelSource source);

    /** Whether this dispute has already produced its label, so redelivery cannot double-count. */
    boolean existsByEvidenceId(UUID evidenceId);

    /** Accounts anyone has labelled at all, for the review queue to skip. */
    @Query("SELECT DISTINCT label.accountId FROM AccountLabel label")
    List<UUID> labelledAccountIds();

    /**
     * Every label a given set of sources produced, newest first.
     *
     * <p>Ordered so that the evaluator can take one verdict per account
     * deterministically when it needs to, rather than depending on whatever
     * order the database felt like returning.
     */
    @Query("""
            SELECT label FROM AccountLabel label
            WHERE label.source IN :sources
            ORDER BY label.observedAt DESC, label.id ASC
            """)
    List<AccountLabel> findBySources(@Param("sources") List<LabelSource> sources);
}
