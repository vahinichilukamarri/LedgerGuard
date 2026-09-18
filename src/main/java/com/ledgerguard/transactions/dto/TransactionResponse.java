package com.ledgerguard.transactions.dto;

import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.dto.PostingResponse;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.Transaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String description,
        String currency,
        Instant createdAt,
        List<PostingResponse> postings) {

    public static TransactionResponse from(PostedTransaction posted) {
        return from(posted.transaction(), posted.postings());
    }

    public static TransactionResponse from(Transaction transaction, List<Posting> postings) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getDescription(),
                transaction.getCurrency(),
                transaction.getCreatedAt(),
                postings.stream().map(PostingResponse::from).toList());
    }
}
