package com.ledgerguard.detection.dto;

import com.ledgerguard.detection.explain.AccountExplanation;
import com.ledgerguard.detection.explain.Reconciliation;
import com.ledgerguard.detection.explain.SignalContribution;
import com.ledgerguard.detection.explain.StatisticalExplanation;
import com.ledgerguard.detection.explain.llm.Narrative;
import com.ledgerguard.detection.explain.llm.NarrativeSource;
import com.ledgerguard.detection.ml.FeatureAttribution;
import com.ledgerguard.detection.ml.MlExplanation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The full explanation for one account.
 *
 * <h2>Why this is its own endpoint</h2>
 *
 * Five signal contributions and eleven feature attributions is roughly four
 * kilobytes of JSON per account. On {@code GET /detection/anomalies} that is
 * fine for one row and drowns the ranking at fifty — the caller asked which
 * accounts to look at, and would receive mostly attribution tables for accounts
 * they are not going to open. So the list carries a digest and this carries the
 * detail, which is the same split the reconciliation endpoints draw between a
 * run and its incidents.
 *
 * <h2>Reused records, deliberately</h2>
 *
 * {@link SignalContribution}, {@link FeatureAttribution} and
 * {@link Reconciliation} go over the wire as themselves rather than through
 * mirror DTOs. Phase 8 and 9 map their domain types because those carry enums,
 * {@code NaN} and {@code Optional}, none of which belong in JSON. These three
 * are flat records of strings, doubles and booleans that were designed in this
 * phase for exactly this purpose, and a mirror class would be a second place
 * for a field to be forgotten.
 *
 * <h2>Which narrative this is</h2>
 *
 * From Phase 11 {@link #summary} may be written by a hosted model rather than
 * by a template, so {@link #narrativeSource} says which. The numbers around it
 * are unaffected either way: the model is given the same evidence the template
 * consumes and is allowed to restate it, never to add to it.
 */
public record AccountExplanationResponse(
        UUID accountId,
        Instant asOf,
        Statistical statistical,
        MachineLearning ml,
        String mlUnavailableReason,
        Reconciliation reconciliation,
        String summary,

        /**
         * {@code TEMPLATE} or {@code LLM}. Not an error field: a template
         * served because a model timed out looks the same here as one served
         * because no model is configured, which is deliberate.
         */
        NarrativeSource narrativeSource,

        List<String> caveats) {

    /**
     * @param applicableWeight the composite's denominator, published because the
     *                         renormalisation is the whole reason a two-signal
     *                         account can score as high as a five-signal one
     */
    public record Statistical(
            double composite,
            int applicableSignals,
            boolean wellEvidenced,
            double applicableWeight,
            List<SignalContribution> contributions) {

        static Statistical from(StatisticalExplanation explanation) {
            return new Statistical(
                    explanation.composite(),
                    explanation.applicableSignals(),
                    explanation.wellEvidenced(),
                    explanation.applicableWeight(),
                    explanation.contributions());
        }
    }

    /**
     * @param attributions all eleven features, not just the isolating ones. A
     *                     feature that pushed this account toward the crowd is a
     *                     finding, and truncating the list would let a reviewer
     *                     read the rest as merely absent
     * @param trainingRows how many accounts the percentiles are against
     */
    public record MachineLearning(
            double score,
            double expectedPathLength,
            int trainingRows,
            AccountAssessmentResponse.Model model,
            List<FeatureAttribution> attributions) {

        static MachineLearning from(MlExplanation explanation) {
            return new MachineLearning(
                    explanation.score(),
                    explanation.expectedPathLength(),
                    explanation.trainingRows(),
                    AccountAssessmentResponse.Model.from(explanation.model()),
                    explanation.attributions());
        }
    }

    /** With Phase 10's deterministic narrative. */
    public static AccountExplanationResponse of(AccountExplanation explanation) {
        return of(explanation, Narrative.template(explanation.summary()));
    }

    public static AccountExplanationResponse of(AccountExplanation explanation, Narrative narrative) {
        return new AccountExplanationResponse(
                explanation.accountId(),
                explanation.asOf(),
                Statistical.from(explanation.statistical()),
                explanation.hasModel() ? MachineLearning.from(explanation.ml()) : null,
                explanation.mlUnavailableReason(),
                explanation.reconciliation(),
                narrative.text(),
                narrative.source(),
                explanation.caveats());
    }
}
