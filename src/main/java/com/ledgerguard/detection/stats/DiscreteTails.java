package com.ledgerguard.detection.stats;

/**
 * Exact upper-tail probabilities for counts and rates, and the surprisal scale
 * every count-based signal is scored on.
 *
 * <h2>Why not a z-score on counts</h2>
 *
 * Because counts are not normal, and the approximation fails worst exactly where
 * this layer operates. The usual trick is {@code (k − λ)/√λ}, which assumes the
 * Poisson is near-normal — true for large λ, badly false for small. An account
 * expected to make 0.2 payments an hour that makes 3 gets
 * {@code (3 − 0.2)/√0.2 ≈ 6.3}, an apparently extreme score, when the honest
 * answer is {@code P(X ≥ 3 | λ=0.2) ≈ 0.0011}: unusual, not extraordinary. Small
 * λ is the common case for a per-account ledger, so the approximation would
 * mislabel ordinary accounts all day.
 *
 * <p>The exact tail costs a short loop and is correct at every λ, so there is no
 * reason to approximate.
 *
 * <h2>Surprisal</h2>
 *
 * Tail probabilities span many orders of magnitude and are awkward to threshold
 * or combine. {@link #surprisal(double)} converts one to {@code −log₁₀ p}, which
 * turns "one in a thousand" into 3 and "one in a hundred million" into 8. That
 * puts every count-based signal on one linear, additive scale, and makes the
 * flagging threshold a round number instead of a string of zeroes.
 */
public final class DiscreteTails {

    /**
     * Above this λ the iterative Poisson sum would need more terms than it is
     * worth, and {@code e^-λ} is heading for underflow ({@code e^-745} is zero
     * in a double). The normal approximation is excellent by then — the very
     * regime where the z-score this class avoids is actually valid.
     */
    private static final double POISSON_NORMAL_APPROXIMATION_ABOVE = 500.0;

    /** Smallest probability the surprisal scale will report, so it stays finite. */
    private static final double MINIMUM_PROBABILITY = 1e-300;

    private DiscreteTails() {
    }

    /**
     * {@code P(X ≥ k)} for {@code X ~ Poisson(lambda)}.
     *
     * <p>Computed as one minus the lower tail, accumulated iteratively:
     * {@code term(0) = e^-λ}, {@code term(i) = term(i-1)·λ/i}. That avoids
     * computing factorials or powers directly, both of which overflow long
     * before the probabilities become interesting.
     */
    public static double poissonUpperTail(int k, double lambda) {
        if (k <= 0) {
            return 1.0;
        }
        if (lambda <= 0) {
            // A process with no rate cannot produce an event. One that does is
            // as surprising as this scale can express.
            return MINIMUM_PROBABILITY;
        }
        if (lambda > POISSON_NORMAL_APPROXIMATION_ABOVE) {
            // Continuity-corrected normal approximation.
            return normalUpperTail((k - 0.5 - lambda) / Math.sqrt(lambda));
        }

        double term = Math.exp(-lambda);
        double lowerTail = term;
        for (int i = 1; i < k; i++) {
            term *= lambda / i;
            lowerTail += term;
        }
        return clampProbability(1.0 - lowerTail);
    }

    /**
     * {@code P(X ≥ k)} for {@code X ~ Binomial(n, p)}.
     *
     * <p>Summed in log space and exponentiated per term. The individual
     * binomial coefficients overflow a double for quite modest {@code n} while
     * the probabilities themselves stay perfectly representable, so the logs are
     * not optional.
     */
    public static double binomialUpperTail(int k, int n, double p) {
        if (k <= 0) {
            return 1.0;
        }
        if (k > n) {
            return 0.0;
        }
        if (p <= 0) {
            return MINIMUM_PROBABILITY;
        }
        if (p >= 1) {
            return 1.0;
        }

        double logP = Math.log(p);
        double logQ = Math.log1p(-p);

        double total = 0;
        for (int i = k; i <= n; i++) {
            total += Math.exp(logChoose(n, i) + i * logP + (n - i) * logQ);
        }
        return clampProbability(total);
    }

    /**
     * {@code −log₁₀(p)}: how many orders of magnitude below certainty an
     * observation sits. A probability of 0.001 is 3.
     */
    public static double surprisal(double probability) {
        return -Math.log10(Math.max(probability, MINIMUM_PROBABILITY));
    }

    /**
     * A rate estimated from {@code successes} out of {@code trials}, with
     * Laplace smoothing.
     *
     * <p>The {@code +1/+2} matters more than it looks. An unsmoothed baseline of
     * zero — no reconciliation mismatch has ever been seen — makes the first
     * mismatch infinitely improbable, and the signal saturates on a single
     * event. Smoothing says the honest thing instead: never having seen one is
     * evidence that they are rare, not proof that they are impossible.
     */
    public static double smoothedRate(long successes, long trials) {
        return (successes + 1.0) / (trials + 2.0);
    }

    // ------------------------------------------------------------- internals

    private static double logChoose(int n, int k) {
        return logFactorial(n) - logFactorial(k) - logFactorial(n - k);
    }

    /** {@code ln(n!)} by summation. Exact enough, and n here is a window count. */
    private static double logFactorial(int n) {
        double total = 0;
        for (int i = 2; i <= n; i++) {
            total += Math.log(i);
        }
        return total;
    }

    /** {@code P(Z ≥ z)} via the complementary error function, Abramowitz-Stegun 7.1.26. */
    private static double normalUpperTail(double z) {
        return clampProbability(0.5 * erfc(z / Math.sqrt(2.0)));
    }

    private static double erfc(double x) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double approximation = t * Math.exp(-x * x - 1.26551223
                + t * (1.00002368
                + t * (0.37409196
                + t * (0.09678418
                + t * (-0.18628806
                + t * (0.27886807
                + t * (-1.13520398
                + t * (1.48851587
                + t * (-0.82215223
                + t * 0.17087277)))))))));
        return x >= 0 ? approximation : 2.0 - approximation;
    }

    /**
     * Floating point subtraction of a lower tail from one can land just outside
     * [0, 1]; a negative probability would become a NaN surprisal.
     */
    private static double clampProbability(double probability) {
        if (Double.isNaN(probability)) {
            return MINIMUM_PROBABILITY;
        }
        return Math.min(1.0, Math.max(MINIMUM_PROBABILITY, probability));
    }
}
