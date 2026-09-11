package com.ledgerguard.detection.ml;

/**
 * How the statistical composite and the isolation score relate for one account.
 *
 * <h2>Why the two scores are never blended</h2>
 *
 * Folding the model in as a sixth Phase 8 signal was considered and rejected,
 * for four reasons that all point the same way:
 *
 * <ol>
 *   <li>It would need a weight — a sixth unfitted judgement stacked on the five
 *       Phase 8 already carries.</li>
 *   <li>It would make the composite <em>partly unexplainable</em>. Every
 *       statistical signal can state its reasoning in a sentence; a forest score
 *       cannot. Blending them produces a number where part of the provenance
 *       simply stops.</li>
 *   <li><b>Disagreement is the most useful thing here.</b> An account the
 *       statistics call quiet and the model calls extreme is the single most
 *       interesting row in the system: either the model has found structure the
 *       signals cannot express, or the model is wrong, and both are worth
 *       knowing. Averaging destroys exactly that information.</li>
 *   <li>Neither score is validated. Combining two unvalidated numbers produces
 *       one unvalidated number that <em>looks more authoritative for being
 *       single</em>, which is the specific failure this phase is meant to avoid.</li>
 * </ol>
 *
 * <p>So both scores travel side by side, and this enum names their relationship
 * rather than resolving it.
 *
 * <h2>The thresholds</h2>
 *
 * "Elevated" needs a cut-off on each scale, and both are <b>conventions, not
 * fitted values</b> — the same caveat that applies to Phase 8's weights.
 *
 * <p>They are deliberately different numbers. The statistical composite is a
 * weighted mean of scores that are zero until their signal fires, so 0.5 there
 * means a substantial share of the signals fired. An isolation score is
 * concentrated around 0.5 <em>by construction</em> — that is the score of a point
 * at average depth — so the same cut-off would call half the population
 * elevated. Using one number for both scales would be tidier and wrong.
 */
public enum Agreement {

    /** Neither score is elevated. */
    BOTH_QUIET,

    /** Both are. The clearest case, and the only one where the two corroborate. */
    BOTH_ELEVATED,

    /**
     * The statistics fired and the model did not. Often means the behaviour is
     * unusual for this account but ordinary across the population.
     */
    STATISTICAL_ONLY,

    /**
     * The model fired and the statistics did not. The row worth reading first:
     * either a combination of features no single signal is shaped to catch, or
     * the model reacting to something that does not matter.
     */
    ML_ONLY;

    /** A composite at or above this counts as elevated. Convention, not fitted. */
    public static final double STATISTICAL_ELEVATED = 0.5;

    /**
     * An isolation score at or above this counts as elevated. Convention, not
     * fitted, and higher than it looks: see the class note on why 0.5 would be
     * meaningless on this scale.
     */
    public static final double ML_ELEVATED = 0.6;

    public static Agreement of(double statisticalScore, double mlScore) {
        boolean statistical = statisticalScore >= STATISTICAL_ELEVATED;
        boolean ml = mlScore >= ML_ELEVATED;

        if (statistical && ml) {
            return BOTH_ELEVATED;
        }
        if (statistical) {
            return STATISTICAL_ONLY;
        }
        if (ml) {
            return ML_ONLY;
        }
        return BOTH_QUIET;
    }
}
