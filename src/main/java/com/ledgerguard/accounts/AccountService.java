package com.ledgerguard.accounts;

import com.ledgerguard.accounts.dto.AccountBalanceResponse;
import com.ledgerguard.postings.PostingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final Clock clock;

    public AccountService(AccountRepository accounts, PostingRepository postings, Clock clock) {
        this.accounts = accounts;
        this.postings = postings;
        this.clock = clock;
    }

    @Transactional
    public Account create(String name, String currency) {
        return accounts.save(Account.create(name, currency, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public Account require(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * Balance derived from the postings ledger, never from a stored field.
     *
     * <p>Reading it costs one indexed aggregate over {@code postings}. That is the
     * deliberate trade: a derived balance cannot disagree with the postings that
     * produced it, which is the whole point of a ledger.
     */
    @Transactional(readOnly = true)
    public AccountBalanceResponse balanceOf(UUID accountId) {
        Account account = require(accountId);
        long balanceMinor = postings.balanceMinorUnits(account.getId(), account.getCurrency());
        return AccountBalanceResponse.of(account.getId(), account.getCurrency(), balanceMinor);
    }
}
