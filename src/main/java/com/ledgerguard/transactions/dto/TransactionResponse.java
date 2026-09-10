package com.ledgerguard.transactions.dto;

import com.ledgerguard.postings.dto.PostingResponse;
import com.ledgerguard.transactions.PostedTransaction;

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
        return new TransactionResponse(
                posted.transaction().getId(),
                posted.transaction().getDescription(),
                posted.transaction().getCurrency(),
                posted.transaction().getCreatedAt(),
                posted.postings().stream().map(PostingResponse::from).toList());
    }
}
