package com.ledgerguard.accounts;

import com.ledgerguard.accounts.dto.AccountBalanceResponse;
import com.ledgerguard.accounts.dto.AccountResponse;
import com.ledgerguard.accounts.dto.CreateAccountRequest;
import com.ledgerguard.config.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Account creation is not itself a Phase 1 deliverable, but the payment flow
     * needs two accounts to exist before it can be exercised at all. This is the
     * minimum needed to make POST /payments verifiable end to end.
     */
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.create(request.name(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}/balance")
    public AccountBalanceResponse balance(@PathVariable UUID id) {
        return accountService.balanceOf(id);
    }

    /** Offset-based paging, newest first, the same defaults as {@code TransactionController}. */
    @GetMapping
    public PageResponse<AccountResponse> list(
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(accountService.list(pageable).map(AccountResponse::from));
    }
}
