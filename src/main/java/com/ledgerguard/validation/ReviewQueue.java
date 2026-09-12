package com.ledgerguard.validation;

import com.ledgerguard.detection.DetectionService;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.MlDetectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which account a reviewer is asked to judge next.
 *
 * <h2>The most consequential class in this phase</h2>
 *
 * It would be natural to hand reviewers the ranking — the accounts the detector
 * is most excited about — and let them work down it. That produces a label set
 * in which <b>every account the detector missed is invisible</b>, and the
 * resulting "recall" is not a weak estimate but a different quantity entirely:
 * of the accounts we flagged, the share reviewers agreed with, which is
 * precision under another name.
 *
 * <p>So the queue draws from two pools:
 *
 * <ul>
 *   <li>{@link Stratum#FLAGGED} — accounts the detector surfaced. Dense in
 *       positives, cheap to review, and biased by construction.</li>
 *   <li>{@link Stratum#AUDIT} — a random sample of accounts it did
 *       <em>not</em> surface. Mostly ordinary, expensive in reviewer patience,
 *       and the only reason a false negative can ever be counted.</li>
 * </ul>
 *
 * <p>This cannot be added later. A label set gathered without an audit stratum
 * can never have one grafted on, because the accounts that would have been
 * sampled are no longer a random sample of anything — they have been filtered
 * by whatever made someone look at them.
 *
 * <h2>Random with respect to the detector, and still reproducible</h2>
 *
 * The audit draw is a seeded shuffle of the unflagged accounts. Seeded, so a run
 * can be reproduced, which Phases 7 through 9 all insist on; and independent of
 * every score, which is the property that matters — the ordering must carry no
 * information about how interesting the detector finds an account.
 *
 * <h2>Blind review</h2>
 *
 * {@link #next} can withhold the scores. A reviewer shown "0.87" before judging
 * is not producing an independent label, and a detector evaluated against
 * anchored labels measures mostly its own influence.
 *
 * <p><b>The limit, stated rather than glossed:</b> this system serves a payload
 * without scores; it cannot stop a reviewer opening the assessment endpoint in
 * another tab. {@code scoresVisible} on the label is therefore an assertion by
 * the caller, not an enforced fact, and the evaluator reports blind and
 * non-blind labels separately so the difference between them is visible rather
 * than assumed away.
 */
@Service
public class ReviewQueue {

    private final DetectionService detection;
    private final MlDetectionService ml;
    private final AccountLabelRepository labels;
    private final double flagThreshold;
    private final long auditSeed;

    public ReviewQueue(DetectionService detection, MlDetectionService ml,
                       AccountLabelRepository labels,
                       @Value("${ledgerguard.validation.flag-threshold:0.5}") double flagThreshold,
                       @Value("${ledgerguard.validation.audit-seed:20260912}") long auditSeed) {
        this.detection = detection;
        this.ml = ml;
        this.labels = labels;
        this.flagThreshold = flagThreshold;
        this.auditSeed = auditSeed;
    }

    /**
     * How many accounts sit in each pool.
     *
     * <p>Published because the two strata are sampled at wildly different rates
     * and their counts therefore cannot simply be added. Each labelled audit
     * account stands for many unlabelled ones, and the evaluator needs these
     * totals to say how many.
     */
    @Transactional(readOnly = true)
    public Census census(Instant asOf) {
        int flagged = 0;
        int unflagged = 0;

        for (DetectionService.ScoredAccount assessed : detection.assessAll(asOf)) {
            if (isFlagged(assessed)) {
                flagged++;
            } else {
                unflagged++;
            }
        }
        return new Census(flagged, unflagged);
    }

    /**
     * The next account to review from {@code stratum}, or empty when that pool
     * is exhausted.
     *
     * <p>Accounts anyone has already labelled are skipped, so two reviewers
     * working the queue do not collide — while a deliberate second opinion
     * remains possible by labelling an account directly, which is how
     * inter-reviewer disagreement gets measured at all.
     */
    @Transactional(readOnly = true)
    public Optional<Candidate> next(Stratum stratum, Instant asOf) {
        Set<UUID> alreadyLabelled = new HashSet<>(labels.labelledAccountIds());

        List<Candidate> pool = new ArrayList<>();
        for (DetectionService.ScoredAccount assessed : detection.assessAll(asOf)) {
            UUID accountId = assessed.score().accountId();
            if (alreadyLabelled.contains(accountId)) {
                continue;
            }
            boolean flagged = isFlagged(assessed);
            if (flagged != (stratum == Stratum.FLAGGED)) {
                continue;
            }
            pool.add(new Candidate(accountId, stratum,
                    assessed.score().composite(),
                    ml.score(assessed).orElse(null),
                    assessed.activity().outboundPayments().size()));
        }

        if (pool.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(switch (stratum) {
            // Worst first: reviewer time on the flagged pool is best spent where
            // the detector is most confident, since that is where a wrong answer
            // costs the most.
            case FLAGGED -> pool.stream()
                    .max((left, right) -> Double.compare(
                            left.statisticalScore(), right.statisticalScore()))
                    .orElseThrow();

            // Independent of every score, so the sample carries no information
            // about how interesting the detector finds an account.
            case AUDIT -> pool.get(indexOf(pool.size(), asOf));
        });
    }

    /**
     * Whether the detector surfaced this account at all.
     *
     * <p>The same rule {@code GET /detection/anomalies} applies, deliberately:
     * the flagged stratum has to mean "what this system would have shown you",
     * or the evaluation is measuring a detector nobody uses.
     */
    public boolean isFlagged(DetectionService.ScoredAccount assessed) {
        if (assessed.score().composite() >= flagThreshold) {
            return true;
        }
        return ml.score(assessed).map(score -> score >= Agreement.ML_ELEVATED).orElse(false);
    }

    /** A deterministic index that depends on nothing the detector computed. */
    private int indexOf(int size, Instant asOf) {
        long mixed = mix(auditSeed, asOf.getEpochSecond());
        return (int) Math.floorMod(mixed, size);
    }

    private static long mix(long seed, long index) {
        long z = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public double flagThreshold() {
        return flagThreshold;
    }

    /**
     * @param statisticalScore withheld from the reviewer under blind review; the
     *                         queue carries it so a non-blind caller need not
     *                         make a second request, and the API decides
     * @param mlScore          null when no model has been trained
     */
    public record Candidate(UUID accountId, Stratum stratum, double statisticalScore,
                            Double mlScore, int paymentsInBaseline) {
    }

    /**
     * @param flagged   accounts the detector surfaced at {@code asOf}
     * @param unflagged accounts it did not, which the audit stratum samples from
     */
    public record Census(int flagged, int unflagged) {

        public int total() {
            return flagged + unflagged;
        }
    }
}
