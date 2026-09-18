package com.ledgerguard.transactions;

import com.ledgerguard.config.PageResponse;
import com.ledgerguard.transactions.dto.TransactionResponse;
import com.ledgerguard.transactions.dto.TransactionSummaryResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** Ledger UI listing: every transaction, or only those touching one account. */
    @GetMapping
    public PageResponse<TransactionSummaryResponse> list(
            @RequestParam(required = false) UUID accountId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        return PageResponse.of(
                transactionService.list(accountId, pageable).map(TransactionSummaryResponse::from));
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return transactionService.get(id);
    }
}
