package com.ledgerguard.detection.ml;

import java.util.Comparator;
import java.util.List;

/**
 * What the forest did to one account, as far as it can be reconstructed.
 *
 * <h2>The standing caveat, in the type that carries the numbers</h2>
 *
 * The score is unvalidated — there is no labelled data anywhere in this system —
 * and the attribution is a reconstruction under one credit rule rather than a
 * unique decomposition. Neither fact is softened by how specific the numbers
 * look. See EXPLANATION_REPORT.md.
 *
 * @param attributions every feature, ordered by excess bits so the isolating
 *                     ones come first. All of them, not just the drivers: a
 *                     feature that pushed the account <em>toward</em> the crowd
 *                     is a real finding, and a truncated list would let a
 *                     reviewer assume the rest were merely absent
 * @param trainingRows how many accounts the percentiles are against
 */
public record MlExplanation(
        double score,
        double expectedPathLength,
        List<FeatureAttribution> attributions,
        int trainingRows,
        ModelMetadata model) {

    public MlExplanation {
        attributions = List.copyOf(attributions);
    }

    static MlExplanation of(IsolationAttribution attribution,
                            double[] values,
                            PopulationProfile population,
                            ModelMetadata model) {

        double positiveExcess = attribution.credits().stream()
                .mapToDouble(FeatureCredit::excessBits)
                .filter(excess -> excess > 0)
                .sum();

        List<FeatureAttribution> attributions = attribution.credits().stream()
                .map(credit -> new FeatureAttribution(
                        credit.name(),
                        credit.index(),
                        values[credit.index()],
                        credit.splitsPerTree(),
                        credit.isolationBits(),
                        credit.excessBits(),
                        credit.excessBits() > 0 && positiveExcess > 0
                                ? credit.excessBits() / positiveExcess
                                : 0.0,
                        population.percentileOf(credit.index(), values[credit.index()]),
                        population.medianOf(credit.index())))
                // Ties broken by name so two runs over one model agree exactly,
                // the same determinism rule the training row order follows.
                .sorted(Comparator.comparingDouble(FeatureAttribution::excessBits).reversed()
                        .thenComparing(FeatureAttribution::feature))
                .toList();

        return new MlExplanation(
                attribution.score(),
                attribution.expectedPathLength(),
                attributions,
                population.size(),
                model);
    }

    /** The features that isolated this account, strongest first. */
    public List<FeatureAttribution> drivers() {
        return attributions.stream().filter(FeatureAttribution::isolating).toList();
    }

    /** The strongest {@code limit} drivers, for a summary that has to stay short. */
    public List<FeatureAttribution> topDrivers(int limit) {
        return drivers().stream().limit(limit).toList();
    }
}
