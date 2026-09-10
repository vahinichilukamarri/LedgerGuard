package com.ledgerguard.reconciliation;

import com.ledgerguard.reconciliation.dto.IncidentResponse;
import com.ledgerguard.reconciliation.dto.RunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    /**
     * Run a pass now and return what it found.
     *
     * <p>No idempotency key: this endpoint moves no money and creates no ledger
     * rows. Running it twice produces two reports of the same facts, which is
     * harmless — unlike running a payment twice.
     */
    @PostMapping("/runs")
    public ResponseEntity<RunResponse> run() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RunResponse.from(reconciliation.run()));
    }

    /** Triage view: filter by any combination of type, severity, status and transaction. */
    @GetMapping("/incidents")
    public List<IncidentResponse> incidents(
            @RequestParam(required = false) DiscrepancyType type,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) UUID transactionId) {

        return reconciliation.findIncidents(type, severity, status, transactionId).stream()
                .map(IncidentResponse::from)
                .toList();
    }

    @PostMapping("/incidents/{id}/resolve")
    public IncidentResponse resolve(@PathVariable UUID id) {
        return IncidentResponse.from(reconciliation.resolve(id));
    }
}
