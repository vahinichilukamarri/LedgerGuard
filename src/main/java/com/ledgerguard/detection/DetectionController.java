package com.ledgerguard.detection;

import com.ledgerguard.detection.dto.AccountAssessmentResponse;
import com.ledgerguard.detection.dto.AccountExplanationResponse;
import com.ledgerguard.detection.explain.AccountExplanation;
import com.ledgerguard.detection.explain.ExplanationService;
import com.ledgerguard.detection.explain.llm.NarrativeService;
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

    /** Asks for the deterministic narrative rather than the model's. */
    private static final String TEMPLATE_NARRATIVE = "template";

    private final DetectionService detection;
    private final MlDetectionService ml;
    private final ExplanationService explanations;
    private final NarrativeService narratives;
    private final Clock clock;

    public DetectionController(DetectionService detection, MlDetectionService ml,
                               ExplanationService explanations, NarrativeService narratives,
                               Clock clock) {
        this.detection = detection;
        this.ml = ml;
        this.explanations = explanations;
        this.narratives = narratives;
        this.clock = clock;
    }

    /**
     * One account, assessed by both layers, with the two scores kept apart and
     * a short explanation of each.
     *
     * <p>The digest, not the full attribution: eleven feature attributions and
     * five signal contributions with prose apiece is a large payload to attach
     * to a score lookup. The summary travels in full, though, including its
     * closing qualification, so a caller that never follows the link to
     * {@code /explanation} still cannot read an unqualified finding.
     *
     * <p>Its narrative is always the template. A score lookup should stay a
     * fast read, and paying a model call to restate numbers the caller may only
     * be scanning is latency spent on the wrong request.
     */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountAssessmentResponse> assess(@PathVariable UUID accountId) {
        AccountExplanation explanation = explanations.explain(accountId, Instant.now(clock));
        return ResponseEntity.ok(
                AccountAssessmentResponse.of(explanation, ExplanationService.NO_MODEL));
    }

    /**
     * Why this account scores what it scores, in full.
     *
     * <p>Its own endpoint rather than a field on the assessment, for the reason
     * the reconciliation endpoints separate a run from its incidents: the
     * detail is an order of magnitude larger than the summary, and a caller
     * scanning a ranking wants to know which account to open, not to receive
     * attribution tables for the forty they will not.
     *
     * <h2>The only place a model is called</h2>
     *
     * Phase 11's LLM narrative is generated here and nowhere else. The ranking
     * returns many accounts, and one model call per row would put the cost and
     * the latency of narration on a page nobody reads in full — fifty calls to
     * help someone choose which one account to open. Here a human has already
     * chosen, so the spend is bounded by attention rather than by page size,
     * and the cache makes a second look free.
     *
     * @param narrative {@code template} to skip the model and take Phase 10's
     *                  deterministic wording. Anything else takes the default,
     *                  which serves the model's prose when it is available and
     *                  passes validation, and the template when it does not
     */
    @GetMapping("/accounts/{accountId}/explanation")
    public ResponseEntity<AccountExplanationResponse> explain(
            @PathVariable UUID accountId,
            @RequestParam(name = "narrative", required = false) String narrative) {

        AccountExplanation explanation = explanations.explain(accountId, Instant.now(clock));
        boolean preferTemplate = TEMPLATE_NARRATIVE.equalsIgnoreCase(narrative);

        return ResponseEntity.ok(AccountExplanationResponse.of(
                explanation, narratives.narrate(explanation, preferTemplate)));
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

        // Explained once per account, and the isolation score read back out of
        // the explanation rather than asked for separately: two calls would walk
        // the forest twice and could, in principle, be read as two numbers.
        List<AccountExplanation> candidates = new ArrayList<>();
        for (DetectionService.ScoredAccount assessed : detection.assessAll(asOf)) {
            AccountExplanation explanation = explanations.explain(assessed);

            boolean statisticallyElevated = assessed.score().composite() >= minScore;
            boolean modelElevated = includeMlOnly && explanation.hasModel()
                    && explanation.ml().score() >= Agreement.ML_ELEVATED;

            if (statisticallyElevated || modelElevated) {
                candidates.add(explanation);
            }
        }

        List<AccountAssessmentResponse> ranked = candidates.stream()
                .sorted(Comparator
                        .comparingDouble((AccountExplanation row) ->
                                row.statistical().composite()).reversed()
                        .thenComparing(Comparator.comparingDouble((AccountExplanation row) ->
                                row.hasModel() ? row.ml().score() : 0.0).reversed())
                        .thenComparing(AccountExplanation::accountId))
                .map(row -> AccountAssessmentResponse.of(row, ExplanationService.NO_MODEL))
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
