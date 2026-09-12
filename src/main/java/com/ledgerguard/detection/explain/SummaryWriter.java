package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.ml.Agreement;
import com.ledgerguard.detection.ml.FeatureAttribution;
import com.ledgerguard.detection.ml.MlExplanation;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an explanation into a few sentences a reviewer can read.
 *
 * <h2>Templates, not a language model</h2>
 *
 * Generating these with an LLM call was considered and rejected, and the reason
 * is not cost:
 *
 * <ol>
 *   <li><b>Determinism.</b> Phases 7, 8 and 9 all hold the line that the same
 *       ledger produces the same output, and the detection tests depend on it.
 *       A sampled model breaks that, and the honest fix — asserting on a
 *       paraphrase — is the flaky test this phase is told not to write.</li>
 *   <li><b>The hedging is the hard requirement.</b> These sentences must never
 *       claim an account is doing something wrong, only that its behaviour
 *       departs from a stated reference. A template cannot drift into
 *       "clearly fraudulent" on a Tuesday; a generative model can, and the
 *       safeguard would be a test asserting the absence of vocabulary it was
 *       free to invent.</li>
 *   <li><b>There is nothing to generate.</b> The content is a closed set of
 *       facts already computed. An LLM would only rephrase them, and would add
 *       a network dependency to a read endpoint to do it.</li>
 * </ol>
 *
 * <p>Where a model would genuinely help is the thing this phase does not have:
 * turning the ledger's raw narrative — counterparties, descriptions, timing
 * against business hours — into context no feature encodes. That is a different
 * capability, not a better renderer for this one.
 *
 * <h2>The vocabulary rule</h2>
 *
 * Every sentence describes a <em>departure from a named reference</em>: this
 * account's own history, or the population the model trained on. Nothing here
 * says an account is fraudulent, suspicious, or doing anything at all — the
 * scores are unvalidated, so those would be claims no part of this system has
 * earned. A test pins the forbidden vocabulary.
 */
public final class SummaryWriter {

    /** Named in a summary; the rest stay in the full attribution list. */
    private static final int NAMED_DRIVERS = 2;

    private SummaryWriter() {
    }

    /**
     * Two to four sentences: what the statistics saw, what the model saw, how
     * the two relate, and what none of it establishes.
     */
    public static String summarise(StatisticalExplanation statistical,
                                   MlExplanation ml,
                                   Reconciliation reconciliation,
                                   String mlUnavailableReason) {

        List<String> sentences = new ArrayList<>();
        sentences.add(statisticalSentence(statistical));

        if (ml == null) {
            sentences.add("No model has been trained (%s), so only the statistical layer is described."
                    .formatted(mlUnavailableReason));
        } else {
            sentences.add(modelSentence(ml));
            sentences.add(reconciliation.narrative());
        }

        sentences.add("This describes how the account's recent behaviour departs from its own "
                + "history and from the population the model was trained on. Neither score is "
                + "validated against known outcomes, so none of it establishes that the activity "
                + "is illegitimate.");

        return String.join(" ", sentences);
    }

    private static String statisticalSentence(StatisticalExplanation statistical) {
        List<SignalContribution> drivers = statistical.drivers();

        if (drivers.isEmpty()) {
            return ("No statistical signal fired: the composite is %.2f over %d applicable signal(s), "
                    + "and %d of five could not judge for want of history.")
                    .formatted(statistical.composite(), statistical.applicableSignals(),
                            statistical.unmeasurable().size());
        }

        String named = drivers.stream()
                .limit(NAMED_DRIVERS)
                .map(driver -> "%s (%.0f%% of the score)"
                        .formatted(driver.signal(), driver.contribution() * 100))
                .reduce((left, right) -> left + " and " + right)
                .orElse("");

        return ("The statistical composite is %.2f over %d applicable signal(s) of five, driven by %s.")
                .formatted(statistical.composite(), statistical.applicableSignals(), named);
    }

    private static String modelSentence(MlExplanation ml) {
        List<FeatureAttribution> drivers = ml.topDrivers(NAMED_DRIVERS);

        String scale = ("the isolation score is %.2f, on a scale where about 0.5 is the middle of "
                + "the distribution rather than a threshold and %.2f is the convention for elevated")
                .formatted(ml.score(), Agreement.ML_ELEVATED);

        if (drivers.isEmpty()) {
            return ("From the model, %s; no feature isolated this account faster than an even split "
                    + "would have, so there is nothing to attribute.")
                    .formatted(scale);
        }

        String named = drivers.stream()
                .map(driver -> ("%s, %.0f%% of the isolation, sitting %s the population median "
                        + "at the %s percentile of %d training accounts")
                        .formatted(driver.feature(), driver.share() * 100, driver.direction(),
                                ordinal(driver.percentile()), ml.trainingRows()))
                .reduce((left, right) -> left + ", and " + right)
                .orElse("");

        return "From the model, %s; it isolated this account chiefly on %s."
                .formatted(scale, named);
    }

    /**
     * The limits that travel with every explanation.
     *
     * <p>Separate strings rather than a paragraph, so a caller can render them
     * as their own block instead of having them read as part of the finding. A
     * caveat buried in prose is a caveat nobody reads.
     */
    public static List<String> caveats(StatisticalExplanation statistical, MlExplanation ml) {
        List<String> caveats = new ArrayList<>();

        caveats.add("Neither score is validated: there is no labelled data in this system, so "
                + "precision and recall are unmeasured rather than approximately known.");
        caveats.add("The statistical weights are unfitted judgement, not learned parameters.");

        if (!statistical.wellEvidenced()) {
            caveats.add(("The composite rests on %d applicable signal(s), so most of the evidence "
                    + "this system can gather was never available for this account.")
                    .formatted(statistical.applicableSignals()));
        }
        if (!statistical.unmeasurable().isEmpty()) {
            caveats.add(("%d of five signals had too little history to judge. That is an absence of "
                    + "evidence, not evidence that those behaviours were absent.")
                    .formatted(statistical.unmeasurable().size()));
        }
        if (ml != null) {
            caveats.add("Feature attribution is reconstructed from this account's paths through the "
                    + "forest under one credit rule. It is not a unique decomposition of the score, "
                    + "and correlated features can take credit a single one of them would have "
                    + "earned alone.");
            caveats.add(("Percentiles are context, not the model's reasoning: they say where a value "
                    + "sits among the %d accounts in the training snapshot, which is a different "
                    + "question from what the forest did with it.")
                    .formatted(ml.trainingRows()));
        }

        return List.copyOf(caveats);
    }

    static String ordinal(double percentile) {
        long rank = Math.round(percentile * 100);
        String suffix = switch ((int) (rank % 100)) {
            case 11, 12, 13 -> "th";
            default -> switch ((int) (rank % 10)) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        };
        return rank + suffix;
    }
}
