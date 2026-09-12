package com.ledgerguard.validation;

import com.ledgerguard.validation.dto.InjectDisputeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator access to the simulated card scheme.
 *
 * <p>Namespaced under {@code /admin} for the same reason the settlement fault
 * endpoints are: none of this belongs in a real deployment. A real system
 * receives chargebacks; it does not issue them to itself. This exists so a
 * demo can produce a fraud label on demand rather than waiting sixty days for
 * one.
 */
@RestController
@RequestMapping("/admin/disputes")
public class DisputeController {

    private final DisputeLabeller disputes;

    public DisputeController(DisputeLabeller disputes) {
        this.disputes = disputes;
    }

    @PostMapping
    public Map<String, Object> raise(@Valid @RequestBody InjectDisputeRequest request) {
        Dispute dispute = disputes.record(
                "CB-MANUAL-" + UUID.randomUUID(),
                request.transactionId(),
                request.reason(),
                request.amountMinor(),
                request.currency() == null ? "USD" : request.currency(),
                request.raisedAt());

        return Map.of(
                "disputeId", dispute.getId(),
                "externalId", dispute.getExternalId(),
                "reason", dispute.getReason().name(),
                "raisedAt", dispute.getRaisedAt(),
                "labelBearing", dispute.getReason().isFraudEvidence());
    }

    /** What the scheme currently believes, for eyeballing during a demo. */
    @GetMapping
    public List<Map<String, Object>> all() {
        return disputes.all().stream()
                .map(dispute -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("externalId", dispute.getExternalId());
                    row.put("transactionReference", dispute.getTransactionReference());
                    row.put("reason", dispute.getReason().name());
                    row.put("labelBearing", dispute.getReason().isFraudEvidence());
                    row.put("paymentAt", dispute.getPaymentAt());
                    row.put("raisedAt", dispute.getRaisedAt());
                    row.put("latencyDays", dispute.latency().toDays());
                    return row;
                })
                .toList();
    }
}
