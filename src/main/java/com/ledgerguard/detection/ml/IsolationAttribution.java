package com.ledgerguard.detection.ml;

import java.util.List;

/**
 * One point's score, taken apart by feature.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * It is a reconstruction of how the forest separated this point, read off the
 * paths the point really took. It is <b>not</b> an additive decomposition of the
 * score: the credits do not sum to it, and they are not Shapley values. Two
 * correlated features can each take credit a single one of them would have
 * earned alone, because whichever was drawn first did the separating and the
 * other was never asked.
 *
 * <p>{@link #score} is produced by {@link IsolationForest#score}, not recomputed
 * here, so the explained score and the published score cannot drift apart.
 *
 * @param expectedPathLength {@code E(h(x))}, inverted from the score rather than
 *                           accumulated a second time, for the same reason
 * @param credits            one per feature, in feature-index order. Ranking is
 *                           the explanation layer's job, not the model's
 */
public record IsolationAttribution(
        double score,
        double expectedPathLength,
        List<FeatureCredit> credits) {

    public IsolationAttribution {
        credits = List.copyOf(credits);
    }
}
