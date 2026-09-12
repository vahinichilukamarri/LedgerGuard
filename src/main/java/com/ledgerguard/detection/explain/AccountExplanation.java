package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.AnomalyScore;
import com.ledgerguard.detection.ml.MlExplanation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything this phase can say about one account, assembled.
 *
 * <h2>Four parts, kept as four parts</h2>
 *
 * The statistical breakdown, the model attribution, how the two relate, and a
 * short prose summary. They are separate fields because they are separate
 * claims with different standing: the statistical contributions are arithmetic
 * anyone can recheck against the formula, the attribution is a reconstruction
 * of a model's behaviour, the reconciliation is an interpretation of the two,
 * and the summary is prose. Collapsing them into one narrative field would give
 * all four the authority of the first.
 *
 * <p>{@link #caveats} travels alongside rather than inside the summary, so a
 * caller renders it as its own block. A limitation folded into a paragraph is a
 * limitation nobody reads.
 *
 * @param ml                  null when no model has been trained. Not a zero
 *                            score and not an empty attribution, both of which
 *                            would be claims the system has not made
 * @param mlUnavailableReason why there is no model attribution, when there is none
 * @param reconciliation      null for the same reason: there is nothing to
 *                            reconcile a statistical score with
 */
public record AccountExplanation(
        UUID accountId,
        Instant asOf,
        StatisticalExplanation statistical,
        MlExplanation ml,
        String mlUnavailableReason,
        Reconciliation reconciliation,
        String summary,
        List<String> caveats) {

    public AccountExplanation {
        caveats = List.copyOf(caveats);
    }

    public static AccountExplanation of(AnomalyScore score,
                                        Optional<MlExplanation> mlExplanation,
                                        String mlUnavailableReason) {

        StatisticalExplanation statistical = StatisticalExplanation.of(score);
        MlExplanation ml = mlExplanation.orElse(null);
        Reconciliation reconciliation = ml == null ? null : Reconciliation.of(statistical, ml);

        return new AccountExplanation(
                score.accountId(),
                score.asOf(),
                statistical,
                ml,
                ml == null ? mlUnavailableReason : null,
                reconciliation,
                SummaryWriter.summarise(statistical, ml, reconciliation, mlUnavailableReason),
                SummaryWriter.caveats(statistical, ml));
    }

    public boolean hasModel() {
        return ml != null;
    }
}
