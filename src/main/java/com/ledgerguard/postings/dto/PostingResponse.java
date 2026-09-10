package com.ledgerguard.postings.dto;

import com.ledgerguard.config.Money;
import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.PostingType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A posting as rendered at the API boundary.
 *
 * <p>{@code amountMinorUnits} is what the ledger stores. {@code amount} is the
 * decimal rendering, produced here and used for nothing else.
 */
public record PostingResponse(
        UUID id,
        UUID accountId,
        PostingType type,
        long amountMinorUnits,
        BigDecimal amount,
        String currency,
        Instant createdAt) {

    public static PostingResponse from(Posting posting) {
        return new PostingResponse(
                posting.getId(),
                posting.getAccountId(),
                posting.getType(),
                posting.getAmountMinor(),
                Money.toMajorUnits(posting.getAmountMinor(), posting.getCurrency()),
                posting.getCurrency(),
                posting.getCreatedAt());
    }
}
