package com.ledgerguard.detection;

import java.util.Collection;

/**
 * How the five signal scores become one number.
 *
 * <h2>Why this is its own class now</h2>
 *
 * Through Phases 8 to 12 the aggregation was four lines inside
 * {@link AnomalyScorer}, which was the right size for a weighted mean. Phase 12
 * then found a defect in it that no test could reach without re-deriving the
 * arithmetic by hand, because there was nothing to call. Putting the function
 * behind a name means {@code CompositeCeilingTest} characterises the thing that
 * actually runs rather than a copy of it that can drift.
 *
 * <h2>The function</h2>
 *
 * <pre>    composite = ( SUM over applicable signals of w_i * s_i^3 ) ^ (1/3)</pre>
 *
 * A weighted power mean of degree three, over the signals that could judge,
 * with the weights taken as they are defined in {@link Signal} and <b>not</b>
 * renormalised.
 *
 * <h2>What it replaces, and why</h2>
 *
 * Phase 8 used the weighted arithmetic mean of the applicable signals,
 * renormalised by their total weight. Phase 12 measured what that costs:
 *
 * <ul>
 *   <li>No single signal, however extreme, could flag an account once three of
 *       the five were applicable. The heaviest weight is 0.25 against a
 *       threshold of 0.50, so one saturated signal reached half the bar at
 *       best.</li>
 *   <li>Accruing history strictly lowered an account's composite. A signal that
 *       gained enough data to say "nothing unusual here" enlarged the
 *       denominator and diluted the ones that were shouting.</li>
 *   <li>Thin-history accounts were therefore <em>easier</em> to flag than
 *       fully-measured ones, which is the opposite of what anyone wanted.</li>
 * </ul>
 *
 * <h2>Why the exponent is three</h2>
 *
 * It is derived, not chosen. The property being bought is: <b>any one signal at
 * saturation elevates any account</b>. A lone saturated signal of weight
 * {@code w} scores {@code w^(1/p)}, so the guarantee needs
 * {@code w_min^(1/p) >= 0.50} for the lightest weight in the system:
 *
 * <pre>    p >= ln(0.15) / ln(0.50) = 2.74</pre>
 *
 * Three is the smallest integer that satisfies it. Two leaves the lightest
 * signal at {@code 0.15^(1/2) = 0.387}, still short of the bar; four would
 * loosen everything further with nothing asking it to. The exponent is pinned
 * by the weights and the threshold that already existed, so this fix introduces
 * no new fitted or unfitted constant — change either of those and the
 * derivation, and the test that guards it, move with them.
 *
 * <h2>Why renormalisation had to go, and not merely the exponent</h2>
 *
 * Phase 8 renormalised for a stated reason: without it "an account with only two
 * measurable signals could never exceed 0.45 however extreme its behaviour, and
 * thin-history accounts would be structurally invisible". That was true — <em>at
 * degree one</em>. At degree three, two saturated signals reach 0.766 unaided.
 * The power transform does the job renormalisation was there to do, so keeping
 * both compensates twice and reintroduces exactly the inversion this change
 * exists to remove.
 *
 * <p>Dropping it also retires a pathology Phase 8 documented and accepted: one
 * applicable signal, saturated, used to report a composite of 1.00 — total
 * certainty from a single piece of evidence. It now reports 0.63.
 *
 * <h2>What the number means now</h2>
 *
 * The composite is a function of <b>what fired</b>, not of what fraction of the
 * measurable evidence fired. An applicable signal that stayed quiet and a signal
 * that could not judge contribute the same nothing. That is a real change and it
 * moves information rather than destroying it: how much of the evidence was
 * measurable lives in {@link AnomalyScore#applicableSignals()} and
 * {@link AnomalyScore#isWellEvidenced()}, which have travelled beside the score
 * since Phase 8 for precisely this purpose.
 *
 * <p>The consequence worth stating: a thin-history account can no longer reach
 * 1.0. Two applicable signals, both saturated, cap at 0.766. The composite now
 * says how much alarming evidence there is, and there is less of it.
 */
public final class CompositeAggregation {

    /**
     * The degree of the power mean. See the class note: derived from the
     * lightest signal weight and the elevation threshold, not chosen.
     */
    public static final int DEGREE = 3;

    private CompositeAggregation() {
    }

    /**
     * Combine the signal outcomes into one score in {@code [0,1]}.
     *
     * <p>Inapplicable signals are skipped rather than counted as zero, which
     * happens to be the same arithmetic — a signal contributing {@code w * 0^3}
     * adds nothing either way — but says the right thing about intent.
     */
    public static double combine(Collection<SignalScore> scores) {
        double sum = 0;
        for (SignalScore score : scores) {
            if (score.applicable()) {
                sum += score.signal().weight() * Math.pow(score.score(), DEGREE);
            }
        }
        return sum <= 0 ? 0.0 : Math.pow(sum, 1.0 / DEGREE);
    }

    /**
     * One signal's part of the sum inside the root, before the root is taken.
     *
     * <p>These are what add up: {@code sum(part) == composite^3}. Published as
     * shares by the explanation layer rather than raw, because a share sums to
     * one and reads as "this signal supplied most of the score", while the raw
     * parts are in a cubed space nobody has intuitions about.
     */
    public static double part(SignalScore score) {
        return score.applicable()
                ? score.signal().weight() * Math.pow(score.score(), DEGREE)
                : 0.0;
    }

    /**
     * The lowest score a lone signal of this weight needs in order to elevate an
     * account by itself.
     *
     * <p>Exposed because it is the number the fix is really about, and because a
     * test asserting it is identical for every applicable set is what pins the
     * inversion closed.
     */
    public static double soloScoreToElevate(double weight, double threshold) {
        return Math.pow(Math.pow(threshold, DEGREE) / weight, 1.0 / DEGREE);
    }
}
