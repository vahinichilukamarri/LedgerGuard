package com.ledgerguard.accounts.dto;

import com.ledgerguard.config.Money;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Balance at the API boundary.
 *
 * <p>{@code balanceMinorUnits} is the authoritative integer value the ledger
 * actually holds. {@code balance} is the same number rendered as a decimal for
 * human display, computed here at the edge and nowhere else.
 */
public record AccountBalanceResponse(
        UUID accountId,
        String currency,
        long balanceMinorUnits,
        BigDecimal balance) {

    public static AccountBalanceResponse of(UUID accountId, String currency, long balanceMinorUnits) {
        return new AccountBalanceResponse(
                accountId, currency, balanceMinorUnits, Money.toMajorUnits(balanceMinorUnits, currency));
    }
}
