package com.ledgerguard.validation;

import com.ledgerguard.detection.DetectionService;
import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.MlDetectionService;
import com.ledgerguard.detection.ml.ModelMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Scores the labelled accounts as of the moment each label describes, and
 * reports what that says about the detector.
 *
 * <h2>The two rules this class exists to enforce</h2>
 *
 * <ol>
 *   <li><b>Score as of the label, never as of now.</b> Every account is assessed
 *       at {@code labelledAsOf}, so a chargeback raised in November is compared
 *       against the detector's view in September. Scoring with today's ledger
 *       would hand the detector every consequence of the fraud — the refunds it
 *       caused, the reconciliation incidents it raised — and produce a superb
 *       number that nothing in production could reproduce.</li>
 *   <li><b>Refuse recall without an audit stratum.</b> If no account the
 *       detector failed to flag was ever labelled, there are no false negatives
 *       to find, and the recall that falls out is 1.0 by construction. The
 *       report returns it as unmeasurable instead.</li>
 * </ol>
 *
 * <h2>Weighting</h2>
 *
 * The strata are sampled at different rates, so raw counts describe a population
 * that does not exist. Each labelled account is weighted by
 * {@code stratum population / stratum labelled} — the design-based estimator —
 * so that one audit account reviewed out of two hundred unflagged ones speaks
 * for all two hundred. The confidence intervals are computed from the
 * <em>unweighted</em> counts, because the weights change what is being estimated
 * and not how much evidence there is for it.
 *
 * <h2>What this class does not do</h2>
 *
 * It does not tune anything. No threshold moves, no weight is fitted, no model
 * retrains. Phase 8's weights and Phase 9's cut-offs are exactly what they were,
 * and the numbers below say what those unfitted choices actually achieve. Fitting
 * them against this label set would produce parameters that describe the label
 * set, and an evaluation on the same labels would then be a measurement of
 * nothing at all.
 */
@Service
public class Evaluator {

    private final DetectionService detection;
    private final MlDetectionService ml;
    private final AccountLabelRepository labels;
    private final LabelService labelService;
    private final ReviewQueue queue;
    private final Clock clock;

    public Evaluator(DetectionService detection, MlDetectionService ml,
                     AccountLabelRepository labels, LabelService labelService,
                     ReviewQueue queue, Clock clock) {
        this.detection = detection;
        this.ml = ml;
        this.labels = labels;
        this.labelService = labelService;
        this.queue = queue;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EvaluationReport evaluate(Set<LabelSource> sources) {
        return evaluate(sources, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public EvaluationReport evaluate(Set<LabelSource> sources, Instant asOf) {
        List<AccountLabel> selected = labels.findBySources(List.copyOf(sources));
        List<AccountLabel> current = oneVerdictPerAccount(selected);

        ReviewQueue.Census census = queue.census(asOf);
        List<String> warnings = new ArrayList<>();

        EvaluationReport.LabelCensus labelCensus = censusOf(selected, current);
        Map<Stratum, Double> weights = weightsFor(current, census);

        List<LabelledScore> scored = score(current, weights, warnings);
        List<LabelledScore> decisive = scored.stream()
                .filter(row -> row.verdict().isDecisive())
                .toList();

        boolean recallMeasurable = labelCensus.auditLabels() > 0
                || sources.contains(LabelSource.SYNTHETIC);

        collectWarnings(warnings, sources, labelCensus, decisive, recallMeasurable);

        return new EvaluationReport(
                asOf,
                warnings,
                labelCensus,
                census,
                layer(decisive, LabelledScore::statisticalScore,
                        queue.flagThreshold(), recallMeasurable),
                layer(decisive.stream().filter(LabelledScore::hasModelScore).toList(),
                        row -> row.mlScore() == null ? Double.NaN : row.mlScore(),
                        Agreement.ML_ELEVATED, recallMeasurable),
                agreementStates(decisive),
                labelService.agreement());
    }

    // ------------------------------------------------------------- scoring

    /**
     * The latest decisive verdict per account.
     *
     * <p>Reviewers revise, and two reviewers disagree. For a confusion matrix
     * one verdict per account is needed, and the most recent is the least
     * arbitrary choice available — but it is a choice, and the disagreement it
     * papers over is reported separately by {@link LabelService#agreement()}
     * rather than lost here.
     */
    private static List<AccountLabel> oneVerdictPerAccount(List<AccountLabel> all) {
        Map<UUID, AccountLabel> latest = new LinkedHashMap<>();
        for (AccountLabel label : all) {
            latest.merge(label.getAccountId(), label, (existing, candidate) ->
                    candidate.getObservedAt().isAfter(existing.getObservedAt()) ? candidate : existing);
        }
        return List.copyOf(latest.values());
    }

    private List<LabelledScore> score(List<AccountLabel> current, Map<Stratum, Double> weights,
                                      List<String> warnings) {
        Optional<ModelMetadata> metadata = ml.metadata();
        int leaky = 0;

        List<LabelledScore> scored = new ArrayList<>(current.size());
        for (AccountLabel label : current) {
            // As of the label, never as of now.
            Instant asOf = label.getLabelledAsOf();
            DetectionService.ScoredAccount assessed;
            try {
                assessed = detection.assess(label.getAccountId(), asOf);
            } catch (RuntimeException accountGone) {
                continue;
            }

            Double modelScore = ml.score(assessed).orElse(null);
            if (modelScore != null && metadata.isPresent()
                    && metadata.get().trainedAsOf().isAfter(asOf)) {
                leaky++;
            }

            scored.add(new LabelledScore(
                    label.getAccountId(), asOf,
                    assessed.score().composite(), modelScore,
                    label.getVerdict(), label.getSource(), label.getStratum(),
                    label.isScoresVisible(),
                    weights.getOrDefault(label.getStratum(), 1.0)));
        }

        if (leaky > 0) {
            warnings.add(("the model was trained on data from after the labelled period for %d of "
                    + "%d accounts; its figures are optimistic by an unknown amount")
                    .formatted(leaky, scored.size()));
        }
        return scored;
    }

    /**
     * How many accounts each labelled one speaks for.
     *
     * <p>Only the human strata are weighted. A dispute label is not a sample
     * from a frame anyone designed — a scheme raised it or did not — so
     * inventing a weight for it would dress an unknown sampling process as a
     * known one.
     */
    private static Map<Stratum, Double> weightsFor(List<AccountLabel> current,
                                                   ReviewQueue.Census census) {
        long flaggedLabels = current.stream()
                .filter(label -> label.getStratum() == Stratum.FLAGGED)
                .count();
        long auditLabels = current.stream()
                .filter(label -> label.getStratum() == Stratum.AUDIT)
                .count();

        Map<Stratum, Double> weights = new EnumMap<>(Stratum.class);
        if (flaggedLabels > 0) {
            weights.put(Stratum.FLAGGED, census.flagged() / (double) flaggedLabels);
        }
        if (auditLabels > 0) {
            weights.put(Stratum.AUDIT, census.unflagged() / (double) auditLabels);
        }
        return weights;
    }

    // ------------------------------------------------------------- metrics

    private static EvaluationReport.LayerResult layer(List<LabelledScore> scored,
                                                     java.util.function.ToDoubleFunction<LabelledScore> scoreOf,
                                                     double threshold,
                                                     boolean recallMeasurable) {
        ConfusionMatrix confusion = ConfusionMatrix.empty();
        long truePositives = 0;
        long flagged = 0;
        long positives = 0;

        for (LabelledScore row : scored) {
            double score = scoreOf.applyAsDouble(row);
            if (Double.isNaN(score)) {
                continue;
            }
            boolean predicted = score >= threshold;
            boolean actual = row.isAnomalous();
            double weight = row.weight();

            confusion = confusion.plus(new ConfusionMatrix(
                    predicted && actual ? weight : 0,
                    predicted && !actual ? weight : 0,
                    !predicted && !actual ? weight : 0,
                    !predicted && actual ? weight : 0,
                    1));

            if (predicted) {
                flagged++;
                if (actual) {
                    truePositives++;
                }
            }
            if (actual) {
                positives++;
            }
        }

        return new EvaluationReport.LayerResult(
                threshold,
                confusion,
                Wilson.interval(truePositives, flagged),
                recallMeasurable
                        ? Wilson.interval(truePositives, positives)
                        : new Wilson.Interval(Double.NaN, 0.0, 1.0, 0),
                recallMeasurable,
                PrecisionRecallCurve.of(scored, scoreOf));
    }

    /**
     * Phase 9's central claim, made testable.
     *
     * <p>That phase kept the two scores apart on the argument that disagreement
     * between them is the most informative thing the pair produces, and called
     * {@code ML_ONLY} "the row worth reading first". Nothing has ever checked
     * it. If the anomalous share of {@code ML_ONLY} turns out to be no better
     * than {@code BOTH_QUIET}, the disagreement layer is surfacing noise, and
     * that would be the most valuable finding available here.
     */
    private static Map<Agreement, EvaluationReport.StateResult> agreementStates(
            List<LabelledScore> scored) {

        Map<Agreement, EvaluationReport.StateResult> states = new EnumMap<>(Agreement.class);
        Map<Agreement, List<LabelledScore>> grouped = new EnumMap<>(Agreement.class);

        for (LabelledScore row : scored) {
            if (!row.hasModelScore()) {
                continue;
            }
            grouped.computeIfAbsent(
                            Agreement.of(row.statisticalScore(), row.mlScore()),
                            key -> new ArrayList<>())
                    .add(row);
        }

        for (Map.Entry<Agreement, List<LabelledScore>> entry : grouped.entrySet()) {
            List<LabelledScore> rows = entry.getValue();
            long anomalous = rows.stream().filter(LabelledScore::isAnomalous).count();
            states.put(entry.getKey(), new EvaluationReport.StateResult(
                    rows.size(),
                    rows.isEmpty() ? Double.NaN : anomalous / (double) rows.size(),
                    Wilson.interval(anomalous, rows.size())));
        }
        return states;
    }

    // ------------------------------------------------------------ warnings

    private static EvaluationReport.LabelCensus censusOf(List<AccountLabel> all,
                                                         List<AccountLabel> current) {
        return new EvaluationReport.LabelCensus(
                all.size(),
                (int) current.stream().filter(label -> label.getVerdict().isDecisive()).count(),
                (int) current.stream().filter(label -> !label.getVerdict().isDecisive()).count(),
                (int) current.stream().filter(label -> label.getStratum() == Stratum.FLAGGED).count(),
                (int) current.stream().filter(label -> label.getStratum() == Stratum.AUDIT).count(),
                (int) current.stream().filter(l -> l.getSource() == LabelSource.DISPUTE_FEED).count(),
                (int) current.stream().filter(l -> l.getSource() == LabelSource.SYNTHETIC).count(),
                (int) current.stream()
                        .filter(label -> label.getSource() == LabelSource.HUMAN_REVIEW)
                        .filter(label -> !label.isScoresVisible())
                        .count());
    }

    /**
     * Everything wrong with this report, in the report.
     *
     * <p>A reader who skips these will quote a precision figure from thirty
     * accounts as though it were a property of the detector. Putting them in the
     * payload rather than in documentation is the only version of this that
     * survives contact with a dashboard.
     */
    private static void collectWarnings(List<String> warnings, Set<LabelSource> sources,
                                        EvaluationReport.LabelCensus census,
                                        List<LabelledScore> decisive, boolean recallMeasurable) {

        if (decisive.isEmpty()) {
            warnings.add("no decisive labels: nothing here is measured");
            return;
        }
        if (!recallMeasurable) {
            warnings.add("no audit-stratum labels: recall is unmeasurable, because an account the "
                    + "detector missed has never been looked at. Precision is unaffected");
        }
        if (census.decisive() < 100) {
            warnings.add(("%d decisive labels is a small sample; read the confidence intervals "
                    + "rather than the point estimates").formatted(census.decisive()));
        }
        if (sources.contains(LabelSource.SYNTHETIC)) {
            warnings.add("synthetic labels are included: these measure whether the detector finds "
                    + "anomalies this system generated, which is circular and is not validation");
        }
        if (census.blindLabels() < census.flaggedStratumLabels() + census.auditLabels()) {
            warnings.add(("%d of %d human labels were made with the scores visible; those verdicts "
                    + "may be anchored on the detector's own output")
                    .formatted(census.flaggedStratumLabels() + census.auditLabels()
                            - census.blindLabels(),
                            census.flaggedStratumLabels() + census.auditLabels()));
        }
        if (census.unclear() > 0) {
            warnings.add(("%d account(s) were reviewed and could not be judged; they are excluded "
                    + "from every figure").formatted(census.unclear()));
        }
    }

    /** Labelled accounts ranked by score, for eyeballing what the detector put on top. */
    @Transactional(readOnly = true)
    public List<LabelledScore> labelledScores(Set<LabelSource> sources, Instant asOf) {
        List<AccountLabel> current = oneVerdictPerAccount(labels.findBySources(List.copyOf(sources)));
        List<String> ignored = new ArrayList<>();
        return score(current, weightsFor(current, queue.census(asOf)), ignored).stream()
                .sorted(Comparator.comparingDouble(LabelledScore::statisticalScore).reversed())
                .toList();
    }
}
