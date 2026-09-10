package com.ledgerguard.reversals;

import com.ledgerguard.idempotency.IdempotencyService;
import com.ledgerguard.reversals.dto.CreateReversalRequest;
import com.ledgerguard.reversals.dto.ReversalResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transactions/{transactionId}/reversals")
public class ReversalController {

    private static final String ENDPOINT = "POST /transactions/{transactionId}/reversals";

    private final ReversalService reversalService;
    private final IdempotencyService idempotency;

    public ReversalController(ReversalService reversalService, IdempotencyService idempotency) {
        this.reversalService = reversalService;
        this.idempotency = idempotency;
    }

    /**
     * The body is optional: a reversal needs nothing but the transaction id, so
     * {@code POST} with no body at all is valid. A description may be supplied.
     *
     * <p>The {@code Idempotency-Key} header is not optional. Reversals are
     * already single-use through a UNIQUE constraint, so a retry would get a
     * 422 rather than double-reverse — but a 422 on a retry that actually
     * succeeded is a confusing answer. With a key, the retry replays the
     * original 201 instead.
     */
    @PostMapping
    public ResponseEntity<String> reverse(
            @PathVariable UUID transactionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) @Nullable CreateReversalRequest request) {

        String path = "/transactions/" + transactionId + "/reversals";

        return idempotency.execute(idempotencyKey, ENDPOINT, "POST", path, request, () -> {
            String description = request == null ? null : request.description();
            ReversalResponse response = reversalService.reverse(transactionId, description);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        });
    }
}
