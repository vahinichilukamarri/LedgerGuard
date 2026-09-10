package com.ledgerguard.accounts.dto;

import com.ledgerguard.accounts.Account;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, String name, String currency, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(), account.getName(), account.getCurrency(), account.getCreatedAt());
    }
}
