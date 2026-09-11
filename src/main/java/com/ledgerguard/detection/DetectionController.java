package com.ledgerguard.detection;

import com.ledgerguard.detection.dto.AccountAssessmentResponse;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.MlDetectionService;
import com.ledgerguard.detection.ml.ModelMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the detection layer, statistical and learned.
 *
 * <p>Everything except training is a GET, and training writes no ledger data —
 * it replaces an in-memory model. No {@code Idempotency-Key} on any of it, for
 * the same reason the reconciliation endpoints take none: nothing here moves
 * money, so running it twice reports the same facts twice rather than doing
 * anything twice.
 */
@RestController
@RequestMapping("/detection")
public class DetectionController {

    private static final String NO_MODEL =
            "no model has been trained; POST /detection/model/train to train one";

    private final DetectionService detection;
    private final MlDetectionService ml;
    private final Clock clock;

    public DetectionController(DetectionService detection, MlDetectionService ml, Clock clock) {
        this.detection = detection;
        this.ml = ml;
        this.clock = clock;
    }

    /** One account, assessed by both layers, with the two scores kept apart. */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountAssessmentResponse> assess(@PathVariable UUID accountId) {
        Instant asOf = Instant.now(clock);
        DetectionService.ScoredAccount assessed = detection.assess(accountId, asOf);

        return ResponseEntity.ok(AccountAssessmentResponse.of(
                assessed.score(), ml.score(assessed), ml.metadata(), NO_MODEL));
    }

    /**
     * Accounts either layer considers elevated, worst first.
     *
     * <p><b>Not ranked by a blended score</b>, because there is no blended score.
     * An account appears if <em>either</em> layer flags it, is ordered by the
     * statistical composite with the isolation score as a tie-break, and carries
     * an {@code agreement} field saying which layer put it there. Ranking by a
     * combined number would have quietly reintroduced the conflation the whole
     * design avoids, and would have buried every {@code ML_ONLY} row beneath
     * accounts the statistics merely mildly disliked.
     *
     * @param minScore        statistical composite at or above which to include
     * @param includeMlOnly   whether to also include accounts only the model
     *                        flags. On by default: those are the rows this
     *                        phase exists to surface
     */
    @GetMapping("/anomalies")
    public ResponseEntity<List<AccountAssessmentResponse>> anomalies(
            @RequestParam(name = "minScore", defaultValue = "0.5") double minScore,
            @RequestParam(name = "includeMlOnly", defaultValue = "true") boolean includeMlOnly) {

        Instant asOf = Instant.now(clock);
        Optional<ModelMetadata> metadata = ml.metadata();

        record Ranked(DetectionService.ScoredAccount assessed, Optional<Double> mlScore) {
        }

        List<Ranked> candidates = new ArrayList<>();
        for (DetectionService.ScoredAccount assessed : detection.assessAll(asOf)) {
            Optional<Double> mlScore = ml.score(assessed);

            boolean statisticallyElevated = assessed.score().composite() >= minScore;
            boolean modelElevated = includeMlOnly && mlScore.isPresent()
                    && mlScore.get() >= Agreement.ML_ELEVATED;

            if (statisticallyElevated || modelElevated) {
                candidates.add(new Ranked(assessed, mlScore));
            }
        }

        List<AccountAssessmentResponse> ranked = candidates.stream()
                .sorted(Comparator
                        .comparingDouble((Ranked row) -> row.assessed().score().composite()).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (Ranked row) -> row.mlScore().orElse(0.0)).reversed())
                        .thenComparing(row -> row.assessed().score().accountId()))
                .map(row -> AccountAssessmentResponse.of(
                        row.assessed().score(), row.mlScore(), metadata, NO_MODEL))
                .toList();

        return ResponseEntity.ok(ranked);
    }

    /**
     * Train a forest on the ledger as it stands.
     *
     * <p>Explicit rather than lazy. A model that appeared as a side effect of the
     * first read would be trained on whatever the ledger held at that moment, by
     * whoever called first, and nobody would know which snapshot they got.
     */
    @PostMapping("/model/train")
    public ResponseEntity<ModelMetadata> train() {
        return ResponseEntity.status(HttpStatus.CREATED).body(ml.train(Instant.now(clock)));
    }

    /** What model is currently loaded, if any. */
    @GetMapping("/model")
    public ResponseEntity<ModelMetadata> model() {
        return ml.metadata()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
