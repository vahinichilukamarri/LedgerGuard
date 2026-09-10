package com.ledgerguard.reversals.dto;

import com.ledgerguard.reversals.Reversal;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.dto.TransactionResponse;

import java.util.UUID;

/**
 * The reversal record plus the new transaction that carries the negating
 * postings. The original transaction is referenced by id only, because nothing
 * about it changed.
 */
public record ReversalResponse(
        UUID reversalId,
        UUID originalTransactionId,
        TransactionResponse reversalTransaction) {

    public static ReversalResponse of(Reversal reversal, PostedTransaction posted) {
        return new ReversalResponse(
                reversal.getId(),
                reversal.getOriginalTransactionId(),
                TransactionResponse.from(posted));
    }
}
