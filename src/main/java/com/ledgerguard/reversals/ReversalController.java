package com.ledgerguard.reversals;

import com.ledgerguard.reversals.dto.CreateReversalRequest;
import com.ledgerguard.reversals.dto.ReversalResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transactions/{transactionId}/reversals")
public class ReversalController {

    private final ReversalService reversalService;

    public ReversalController(ReversalService reversalService) {
        this.reversalService = reversalService;
    }

    /**
     * The body is optional: a reversal needs nothing but the transaction id, so
     * {@code POST} with no body at all is valid. A description may be supplied.
     */
    @PostMapping
    public ResponseEntity<ReversalResponse> reverse(
            @PathVariable UUID transactionId,
            @Valid @RequestBody(required = false) @Nullable CreateReversalRequest request) {

        String description = request == null ? null : request.description();
        ReversalResponse response = reversalService.reverse(transactionId, description);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
