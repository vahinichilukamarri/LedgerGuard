package com.ledgerguard.transactions.dto;

import com.ledgerguard.transactions.Transaction;

import java.time.Instant;
import java.util.UUID;

/**
 * A transaction as it appears in the list view: enough to identify and triage
 * it, without its postings. Fetching every leg for every row of a list would
 * be an extra query per transaction for data the list view doesn't render;
 * {@code GET /transactions/{id}} returns the full {@link TransactionResponse}
 * with postings for the cases that need them.
 */
public record TransactionSummaryResponse(UUID id, String description, String currency, Instant createdAt) {

    public static TransactionSummaryResponse from(Transaction transaction) {
        return new TransactionSummaryResponse(
                transaction.getId(), transaction.getDescription(), transaction.getCurrency(),
                transaction.getCreatedAt());
    }
}
