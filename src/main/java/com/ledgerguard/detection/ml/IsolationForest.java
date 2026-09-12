package com.ledgerguard.detection.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * An Isolation Forest, built as Algorithm 1 of Liu, Ting and Zhou specifies.
 *
 * <blockquote>
 * F. T. Liu, K. M. Ting, Z.-H. Zhou, <i>Isolation Forest</i>, ICDM 2008.
 * </blockquote>
 *
 * <h2>Why this is hand-rolled</h2>
 *
 * Writing a machine learning algorithm from scratch is usually a poor trade.
 * Three things make it the right one here:
 *
 * <ol>
 *   <li>The algorithm is small and completely specified. Everything below
 *       corresponds to a numbered algorithm or equation in a nine-page paper,
 *       and the citations are inline so the implementation can be checked
 *       against the source line by line.</li>
 *   <li>The tests this phase requires — on tree construction, on path length, on
 *       the score formula — cannot be written against a library's internals or
 *       across a sidecar's process boundary. A dependency would have made the
 *       required tests impossible rather than merely inconvenient.</li>
 *   <li>Determinism is a hard requirement carried from Phases 7 and 8, and it is
 *       only achievable if the random number generation is under local control.
 *       A library's internal use of its RNG is not part of its contract and can
 *       change between versions.</li>
 * </ol>
 *
 * <p>What is given up: none of scikit-learn's battle-testing, and any later want
 * of Extended Isolation Forest or SCiForest would mean writing those too.
 *
 * <h2>Scoring</h2>
 *
 * Equation 2 of the paper:
 *
 * <pre>    s(x, ψ) = 2 ^ ( −E(h(x)) / c(ψ) )</pre>
 *
 * where {@code E(h(x))} is the mean path length across the trees and
 * {@code c(ψ)} normalises it by the average path length of an unsuccessful
 * binary search tree lookup over ψ points, Equation 1:
 *
 * <pre>    c(n) = 2·H(n−1) − 2(n−1)/n,    H(i) ≈ ln(i) + γ</pre>
 *
 * <p>The result is already in {@code [0,1]}, which is why no extra
 * normalisation layer sits between the model and the rest of the system — a
 * layer like that is somewhere for a bug to hide, and the algorithm makes it
 * unnecessary.
 *
 * <p><b>Reading the number.</b> Scores concentrate near 0.5 by construction: a
 * point of average depth scores exactly 0.5, so <em>0.5 is the middle of the
 * distribution, not a threshold</em>. Values approaching 1 mean isolated far
 * sooner than average. In practice a raw score above about 0.6 is already
 * notable, and nothing in real data approaches 1.0. Anyone reading these scores
 * as if 0.5 meant "half anomalous" will misread every one of them.
 */
public final class IsolationForest {

    /** Euler–Mascheroni constant, for the harmonic number approximation in Equation 1. */
    private static final double EULER_MASCHERONI = 0.5772156649015329;

    /**
     * The smallest training set that produces a usable forest.
     *
     * <p>This is a floor on the arithmetic, not a judgement about how much data
     * makes a <em>good</em> model — that gate lives in the service layer and is
     * far higher. See the note in {@link #train}.
     */
    private static final int MINIMUM_TRAINING_ROWS = 2;

    private final List<IsolationTree> trees;
    private final int subSampleSize;
    private final double normalisingPathLength;
    private final int dimension;

    private IsolationForest(List<IsolationTree> trees, int subSampleSize, int dimension) {
        this.trees = List.copyOf(trees);
        this.subSampleSize = subSampleSize;
        this.dimension = dimension;
        this.normalisingPathLength = averagePathLength(subSampleSize);
    }

    /**
     * Algorithm 1: {@code t} trees, each over a subsample of {@code ψ} rows, each
     * height-limited to {@code ceil(log2(ψ))}.
     *
     * <p>Subsampling is not a performance shortcut, it is central to how the
     * method works. The paper's own analysis shows that a forest trained on the
     * full data suffers <em>swamping</em> and <em>masking</em> — dense regions
     * grow large enough to hide anomalies inside them — and that small subsamples
     * make each tree see a sparser, more separable version of the problem.
     *
     * @param data  training rows, all of the same length; not modified
     * @param seed  the master seed. Every tree derives its own generator from it,
     *              so the whole forest is a pure function of (data, seed,
     *              treeCount, subSampleSize)
     */
    public static IsolationForest train(List<double[]> data, long seed, int treeCount, int subSampleSize) {
        // Two, not one. With a subsample of a single point c(ψ) is zero by
        // Equation 1, and Equation 2 then divides by it: every score comes back
        // NaN, silently, for every input. A forest over one observation cannot
        // isolate anything from anything, so refusing is the honest answer and
        // keeps the failure at construction rather than in the scores.
        if (data == null || data.size() < MINIMUM_TRAINING_ROWS) {
            throw new IllegalArgumentException(
                    "a forest needs at least %d rows to isolate anything, got: %d"
                            .formatted(MINIMUM_TRAINING_ROWS, data == null ? 0 : data.size()));
        }
        if (treeCount <= 0) {
            throw new IllegalArgumentException("a forest needs at least one tree, got: " + treeCount);
        }

        int dimension = data.get(0).length;
        for (double[] row : data) {
            if (row.length != dimension) {
                throw new IllegalArgumentException(
                        "every training row must have the same length; expected %d, got %d"
                                .formatted(dimension, row.length));
            }
        }

        int effectiveSubSample = Math.min(subSampleSize, data.size());
        int heightLimit = Math.max(1, (int) Math.ceil(log2(effectiveSubSample)));

        List<IsolationTree> trees = new ArrayList<>(treeCount);
        for (int index = 0; index < treeCount; index++) {
            // Each tree gets a generator derived from the master seed by a
            // bit-mixing function rather than by seed + index. Consecutive seeds
            // give java.util.Random highly correlated initial output, which would
            // make neighbouring trees resemble each other and quietly reduce the
            // forest's effective size.
            Random random = new Random(mix(seed, index));
            trees.add(IsolationTree.build(subsample(data, effectiveSubSample, random), heightLimit, random));
        }

        return new IsolationForest(trees, effectiveSubSample, dimension);
    }

    /**
     * The anomaly score for one point, in {@code [0,1]}. Equation 2.
     */
    public double score(double[] point) {
        if (point.length != dimension) {
            throw new IllegalArgumentException(
                    "expected a %d-feature point, got %d".formatted(dimension, point.length));
        }

        double total = 0;
        for (IsolationTree tree : trees) {
            total += tree.pathLength(point);
        }
        double expectedPathLength = total / trees.size();

        return Math.pow(2, -expectedPathLength / normalisingPathLength);
    }

    public double score(FeatureVector features) {
        return score(features.toArray());
    }

    /**
     * The same score, taken apart by feature.
     *
     * <h2>Why an Isolation Forest needs this built by hand</h2>
     *
     * A linear model publishes coefficients and a decision tree classifier
     * publishes impurity gains. A forest of random trees publishes a mean path
     * length and nothing else, so any feature-level account of a score is a
     * reconstruction. This one walks the point down every tree a second time and
     * credits each split with {@code log2(n/m)} bits of isolation — see
     * {@link IsolationTree#attribute} for the rule and why it is bits rather
     * than edges.
     *
     * <p>Cost is one extra traversal per tree: the same order as scoring, which
     * is what makes it affordable on a list endpoint. The alternative considered
     * was leave-one-feature-out re-scoring, which costs the dimension times as
     * much and explains a point with a substituted value rather than this one.
     *
     * <h2>The score is not recomputed</h2>
     *
     * It comes from {@link #score}, and {@code E(h(x))} is inverted back out of
     * it by Equation 2 rather than accumulated again during the walk. A second
     * accumulation would be a second definition of the same quantity, and the
     * two would eventually disagree by a rounding step in a way that made the
     * explanation look wrong about the score it was explaining.
     */
    public IsolationAttribution attribute(double[] point) {
        double score = score(point);

        double[] bits = new double[dimension];
        double[] splits = new double[dimension];
        for (IsolationTree tree : trees) {
            tree.attribute(point, bits, splits);
        }

        List<FeatureCredit> credits = new ArrayList<>(dimension);
        for (int index = 0; index < dimension; index++) {
            double splitsPerTree = splits[index] / trees.size();
            double isolationBits = bits[index] / trees.size();
            credits.add(new FeatureCredit(
                    index,
                    index < FeatureVector.NAMES.length ? FeatureVector.NAMES[index] : "feature_" + index,
                    splitsPerTree,
                    isolationBits,
                    isolationBits - splitsPerTree));
        }

        // E(h(x)) = -log2(s) * c(psi), Equation 2 rearranged.
        double expectedPathLength = -(Math.log(score) / Math.log(2)) * normalisingPathLength;

        return new IsolationAttribution(score, expectedPathLength, credits);
    }

    public IsolationAttribution attribute(FeatureVector features) {
        return attribute(features.toArray());
    }

    /**
     * {@code c(n)} from Equation 1: the average path length of an unsuccessful
     * search in a binary search tree over {@code n} points.
     *
     * <p>This is what makes scores comparable across forests trained on
     * differently sized samples — without it, a deeper tree would look like
     * evidence of normality when it was only evidence of more data.
     */
    public static double averagePathLength(int n) {
        if (n <= 1) {
            return 0.0;
        }
        if (n == 2) {
            return 1.0;
        }
        double harmonic = Math.log(n - 1.0) + EULER_MASCHERONI;
        return 2.0 * harmonic - (2.0 * (n - 1.0) / n);
    }

    public int treeCount() {
        return trees.size();
    }

    public int subSampleSize() {
        return subSampleSize;
    }

    public int dimension() {
        return dimension;
    }

    // ------------------------------------------------------------ internals

    /**
     * {@code size} rows drawn without replacement, by a partial Fisher–Yates
     * shuffle over an index array.
     *
     * <p>Deterministic given the generator, and it touches only the first
     * {@code size} positions rather than shuffling the whole list, so cost does
     * not grow with the training set.
     */
    private static List<double[]> subsample(List<double[]> data, int size, Random random) {
        int[] indices = new int[data.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }

        List<double[]> sample = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int pick = i + random.nextInt(indices.length - i);
            int swap = indices[pick];
            indices[pick] = indices[i];
            indices[i] = swap;
            sample.add(data.get(swap));
        }
        return sample;
    }

    /**
     * SplitMix64 finalising mix, used to derive independent per-tree seeds from
     * one master seed. See the note in {@link #train}.
     */
    private static long mix(long seed, int index) {
        long z = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double log2(int value) {
        return Math.log(value) / Math.log(2);
    }
}
