package com.ledgerguard.settlement;

import com.ledgerguard.settlement.dto.InjectFaultRequest;
import com.ledgerguard.settlement.dto.SettlementRecordResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator access to the simulated processor.
 *
 * <p>Namespaced under {@code /admin} because none of this belongs in a real
 * deployment: it exists to make each discrepancy type reproducible on demand
 * rather than something you wait for.
 */
@RestController
@RequestMapping("/admin/settlement")
public class SettlementController {

    private final SettlementFaultService faults;
    private final SettlementRecordRepository records;

    public SettlementController(SettlementFaultService faults, SettlementRecordRepository records) {
        this.faults = faults;
        this.records = records;
    }

    @PostMapping("/faults")
    public ResponseEntity<Map<String, String>> injectFault(@Valid @RequestBody InjectFaultRequest request) {
        String outcome = faults.inject(request);
        return ResponseEntity.ok(Map.of("fault", request.type().name(), "outcome", outcome));
    }

    /** What the external world currently believes, for eyeballing during a demo. */
    @GetMapping("/records")
    public List<SettlementRecordResponse> records(@RequestParam(required = false) UUID transactionId) {
        List<SettlementRecord> found = transactionId == null
                ? records.findAll()
                : records.findByExternalReference(transactionId.toString());
        return found.stream().map(SettlementRecordResponse::from).toList();
    }
}
