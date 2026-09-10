package com.ledgerguard.refunds;

import com.ledgerguard.config.Money;
import com.ledgerguard.idempotency.IdempotencyService;
import com.ledgerguard.payments.PaymentNotFoundException;
import com.ledgerguard.payments.PaymentRepository;
import com.ledgerguard.refunds.dto.CreateRefundRequest;
import com.ledgerguard.refunds.dto.RefundResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/payments/{paymentId}/refunds")
public class RefundController {

    private static final String ENDPOINT = "POST /payments/{paymentId}/refunds";

    private final RefundService refundService;
    private final PaymentRepository payments;
    private final IdempotencyService idempotency;

    public RefundController(RefundService refundService, PaymentRepository payments,
                            IdempotencyService idempotency) {
        this.refundService = refundService;
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<String> refund(
            @PathVariable UUID paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {

        // The concrete path goes into the fingerprint, so the same key reused
        // against a different payment is a conflict rather than a replay.
        String path = "/payments/" + paymentId + "/refunds";

        return idempotency.execute(idempotencyKey, ENDPOINT, "POST", path, request, () -> {
            // The API boundary again: the decimal becomes minor units here and
            // nowhere deeper. The currency is the payment's, never the caller's.
            String currency = payments.findById(paymentId)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId))
                    .getCurrency();

            long amountMinor = Money.toMinorUnits(request.amount(), currency);

            RefundResponse response = refundService.refund(paymentId, amountMinor, request.description());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        });
    }
}
