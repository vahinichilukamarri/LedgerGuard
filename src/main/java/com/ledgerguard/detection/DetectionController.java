package com.ledgerguard.detection;

import com.ledgerguard.detection.dto.AnomalyScoreResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Reads the detection layer. Nothing here writes.
 *
 * <p>No {@code Idempotency-Key} on either endpoint, for the same reason the
 * reconciliation reads do not take one: scoring twice reports the same facts
 * twice rather than doing anything twice. Scoring is a pure function of ledger
 * rows and the instant it is asked about.
 */
@RestController
@RequestMapping("/detection")
public class DetectionController {

    private final DetectionService detection;

    public DetectionController(DetectionService detection) {
        this.detection = detection;
    }

    /** One account, scored now, with every signal's reasoning attached. */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AnomalyScoreResponse> scoreAccount(@PathVariable UUID accountId) {
        return ResponseEntity.ok(AnomalyScoreResponse.of(detection.scoreAccount(accountId)));
    }

    /**
     * Every account scoring at or above {@code minScore}, worst first.
     *
     * <p>The default of 0.5 is a triage convenience rather than a claim about
     * where anomaly begins — with unfitted weights, no cut-off here would mean
     * anything. Pass {@code minScore=0} to see the whole ranking, which is the
     * honest way to look at it until the weights are learned.
     */
    @GetMapping("/anomalies")
    public ResponseEntity<List<AnomalyScoreResponse>> anomalies(
            @RequestParam(name = "minScore", defaultValue = "0.5") double minScore) {

        List<AnomalyScoreResponse> ranked = detection.rankAccounts(minScore).stream()
                .map(AnomalyScoreResponse::of)
                .toList();
        return ResponseEntity.ok(ranked);
    }
}
