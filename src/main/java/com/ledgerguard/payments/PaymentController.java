package com.ledgerguard.payments;

import com.ledgerguard.config.Money;
import com.ledgerguard.idempotency.IdempotencyService;
import com.ledgerguard.payments.dto.CreatePaymentRequest;
import com.ledgerguard.payments.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private static final String ENDPOINT = "POST /payments";

    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.idempotency = idempotency;
    }

    /**
     * Returns pre-serialized JSON rather than the DTO so that a replay is
     * byte-identical to the original: both paths hand back the very same string
     * that was stored, instead of two separate serializations that merely ought
     * to agree.
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        return idempotency.execute(idempotencyKey, ENDPOINT, "POST", "/payments", request, () -> {
            // The API boundary, and the only place a BigDecimal amount becomes ledger money.
            long amountMinor = Money.toMinorUnits(request.amount(), request.currency());

            PaymentResponse response = paymentService.create(
                    request.sourceAccountId(),
                    request.destinationAccountId(),
                    amountMinor,
                    request.currency(),
                    request.description());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        });
    }
}
