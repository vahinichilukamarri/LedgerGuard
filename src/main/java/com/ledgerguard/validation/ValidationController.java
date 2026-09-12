package com.ledgerguard.validation;

import com.ledgerguard.validation.dto.LabelResponse;
import com.ledgerguard.validation.dto.RecordLabelRequest;
import com.ledgerguard.validation.dto.ReviewCandidateResponse;
import com.ledgerguard.validation.dto.ValidationReportResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Labelling, and what the labels say about the detector.
 *
 * <h2>The one write path in the detection story</h2>
 *
 * {@code POST /validation/labels} is the first endpoint in Phases 8 to 12 that
 * writes anything. It writes an opinion, never a score: nothing here can change
 * what the detector computes, only what is recorded about whether it was right.
 * That separation is what lets the evaluation be a test rather than a
 * self-assessment.
 *
 * <p>No {@code Idempotency-Key}, for the reason the reconciliation endpoints
 * take none: nothing moves money. The uniqueness that matters comes from the
 * data itself — one reviewer, one account, one instant — so a retry returns the
 * existing verdict rather than inventing a second one.
 */
@RestController
@RequestMapping("/validation")
public class ValidationController {

    private final LabelService labels;
    private final ReviewQueue queue;
    private final Evaluator evaluator;
    private final DisputeLabeller disputes;
    private final Clock clock;

    public ValidationController(LabelService labels, ReviewQueue queue, Evaluator evaluator,
                                DisputeLabeller disputes, Clock clock) {
        this.labels = labels;
        this.queue = queue;
        this.evaluator = evaluator;
        this.disputes = disputes;
        this.clock = clock;
    }

    /** Record a reviewer's verdict. Replaying the same verdict returns the original. */
    @PostMapping("/labels")
    public ResponseEntity<LabelResponse> record(@Valid @RequestBody RecordLabelRequest request) {
        AccountLabel label = labels.record(
                request.accountId(), request.verdict(), request.reviewer(), request.stratum(),
                request.scoresVisible(), request.labelledAsOf(), request.notes());

        return ResponseEntity.status(HttpStatus.CREATED).body(LabelResponse.from(label));
    }

    /**
     * Every label on an account, oldest and newest alike.
     *
     * <p>All of them, because a revised verdict does not delete the first one and
     * a second reviewer's disagreement is the most informative thing this
     * endpoint can show.
     */
    @GetMapping("/labels/{accountId}")
    public List<LabelResponse> forAccount(@PathVariable UUID accountId) {
        return labels.forAccount(accountId).stream().map(LabelResponse::from).toList();
    }

    /**
     * The next account to judge.
     *
     * @param stratum {@code FLAGGED} draws from what the detector surfaced;
     *                {@code AUDIT} draws at random from what it did not, and is
     *                the only source of false negatives this system will ever
     *                have
     * @param blind   withhold the scores. Defaults to true: seeing them is the
     *                thing a caller has to ask for, not the thing they have to
     *                opt out of
     */
    @GetMapping("/review/next")
    public ResponseEntity<ReviewCandidateResponse> next(
            @RequestParam(name = "stratum", defaultValue = "FLAGGED") Stratum stratum,
            @RequestParam(name = "blind", defaultValue = "true") boolean blind) {

        return queue.next(stratum, Instant.now(clock))
                .map(candidate -> ResponseEntity.ok(ReviewCandidateResponse.of(candidate, blind)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** How many accounts sit in each pool, which is what makes the weighting checkable. */
    @GetMapping("/review/census")
    public ReviewQueue.Census census() {
        return queue.census(Instant.now(clock));
    }

    /**
     * What the labels say about the detector.
     *
     * @param sources which labels to use. Defaults to the two that are evidence
     *                about the real world; adding {@code SYNTHETIC} is possible
     *                and adds a warning saying the result is circular
     */
    @GetMapping("/report")
    public ValidationReportResponse report(
            @RequestParam(name = "sources", required = false) List<LabelSource> sources) {

        Set<LabelSource> selected = sources == null || sources.isEmpty()
                ? EnumSet.of(LabelSource.HUMAN_REVIEW, LabelSource.DISPUTE_FEED)
                : EnumSet.copyOf(sources);

        return ValidationReportResponse.from(evaluator.evaluate(selected, Instant.now(clock)));
    }

    /**
     * Convert matured chargebacks into labels.
     *
     * <p>Explicit rather than scheduled, for the reason Phase 9 trains its model
     * explicitly: a label set that grew as a side effect of the clock would make
     * an evaluation unreproducible, and nobody could say which snapshot a
     * published figure came from.
     */
    @PostMapping("/labels/from-disputes")
    public ResponseEntity<java.util.Map<String, Integer>> labelFromDisputes() {
        int written = disputes.labelMatured(Instant.now(clock));
        return ResponseEntity.ok(java.util.Map.of(
                "labelsWritten", written,
                "disputesNotYetRaised", disputes.pendingCount()));
    }
}
