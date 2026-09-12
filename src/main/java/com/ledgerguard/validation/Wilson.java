package com.ledgerguard.validation;

/**
 * A confidence interval for a proportion, by Wilson's method.
 *
 * <h2>Why an interval at all</h2>
 *
 * Because the first honest evaluations of this system will rest on a few dozen
 * labels, and "precision 0.83" from twelve accounts is not a measurement, it is
 * an anecdote with a decimal point. The interval is what stops a reader treating
 * the two the same way: at twelve labels it comes back roughly [0.55, 0.95],
 * which says plainly that almost nothing has been established.
 *
 * <h2>Why Wilson and not the textbook formula</h2>
 *
 * The normal approximation {@code p ± z·sqrt(p(1-p)/n)} fails exactly where this
 * system lives — small samples and proportions near 0 or 1. At 10 out of 10 it
 * reports an interval of zero width, which would let a report claim perfect
 * precision with certainty from ten accounts. Wilson's interval stays inside
 * {@code [0,1]}, never collapses to a point, and behaves sensibly when a count
 * is zero.
 */
public final class Wilson {

    /** 95%, two-sided. */
    private static final double Z = 1.959963984540054;

    private Wilson() {
    }

    /**
     * @param successes how many of the trials were positive
     * @param trials    how many there were. Zero yields the whole unit interval,
     *                  which is the correct statement about a quantity nobody
     *                  has measured
     */
    public static Interval interval(long successes, long trials) {
        if (trials <= 0) {
            return new Interval(Double.NaN, 0.0, 1.0, 0);
        }

        double n = trials;
        double p = successes / n;
        double z2 = Z * Z;

        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2 * n)) / denominator;
        double spread = (Z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n))) / denominator;

        return new Interval(p, Math.max(0.0, centre - spread), Math.min(1.0, centre + spread), trials);
    }

    /**
     * @param point the observed proportion, or {@code NaN} when nothing was
     *              measured. NaN rather than zero, because an unmeasured rate is
     *              not a rate of zero
     * @param width how wide the interval is; the honest headline when it is wide
     */
    public record Interval(double point, double low, double high, long trials) {

        public double width() {
            return high - low;
        }

        /** Whether this rests on enough observations to be worth quoting. */
        public boolean isInformative() {
            return trials > 0 && width() < 0.4;
        }
    }
}
