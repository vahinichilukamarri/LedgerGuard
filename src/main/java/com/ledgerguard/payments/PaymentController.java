package com.ledgerguard.payments;

import com.ledgerguard.config.Money;
import com.ledgerguard.payments.dto.CreatePaymentRequest;
import com.ledgerguard.payments.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        // The API boundary, and the only place a BigDecimal amount becomes ledger money.
        long amountMinor = Money.toMinorUnits(request.amount(), request.currency());

        PaymentResponse response = paymentService.create(
                request.sourceAccountId(),
                request.destinationAccountId(),
                amountMinor,
                request.currency(),
                request.description());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
