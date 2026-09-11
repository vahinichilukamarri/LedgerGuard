package com.ledgerguard.detection.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One isolation tree, built exactly as Algorithm 2 of Liu, Ting and Zhou
 * specifies.
 *
 * <blockquote>
 * F. T. Liu, K. M. Ting, Z.-H. Zhou, <i>Isolation Forest</i>, ICDM 2008.
 * </blockquote>
 *
 * <h2>The idea in one paragraph</h2>
 *
 * Split the data on a random feature at a random value, repeatedly. A point in a
 * dense region needs many splits before it ends up alone; a point far from
 * everything else gets separated almost immediately. So the <em>depth at which a
 * point becomes isolated</em> is itself an anomaly score, and no distance metric,
 * density estimate or distribution assumption is needed to compute it. That is
 * why the algorithm is a good fit here: the features in {@link FeatureVector}
 * are on wildly different scales and none of them is normally distributed.
 *
 * <h2>Algorithm 2, transcribed</h2>
 *
 * <pre>
 *   iTree(X, e, l):
 *     if e >= l or |X| <= 1:
 *         return exNode{Size = |X|}
 *     else:
 *         q <- randomly select an attribute
 *         p <- randomly select a split point between min(X_q) and max(X_q)
 *         X_l <- filter(X, X_q < p)
 *         X_r <- filter(X, X_q >= p)
 *         return inNode{Left  = iTree(X_l, e+1, l),
 *                       Right = iTree(X_r, e+1, l),
 *                       SplitAtt = q, SplitValue = p}
 * </pre>
 *
 * <p>{@code e} is the current depth and {@code l} the height limit, which
 * Algorithm 1 sets to {@code ceil(log2(ψ))} — roughly the average depth of a
 * balanced tree over the subsample, past which further splitting tells you
 * nothing new about anomalies because they are isolated long before it.
 *
 * <h2>Two cases the paper does not spell out</h2>
 *
 * Both are real and both are handled explicitly rather than left to chance:
 *
 * <ul>
 *   <li><b>A constant attribute.</b> If {@code min(X_q) == max(X_q)} there is no
 *       split point between them. Selecting uniformly from attributes and
 *       retrying could loop forever when every attribute is constant, so the
 *       selection is made from attributes that actually vary in this subsample,
 *       and a node where none do becomes external. This slightly changes the
 *       attribute distribution — a feature constant in one subsample is never
 *       chosen there — which is the accepted trade in every practical
 *       implementation.</li>
 *   <li><b>An empty partition.</b> The split point is drawn from
 *       {@code [min, max)}, so it can land exactly on the minimum and leave the
 *       left side empty. Such a node has isolated nothing, so it becomes
 *       external rather than burning a level of depth.</li>
 * </ul>
 */
final class IsolationTree {

    /** Internal when {@code left != null}; external otherwise. */
    private record Node(int splitAttribute, double splitValue, Node left, Node right, int size) {

        static Node external(int size) {
            return new Node(-1, Double.NaN, null, null, size);
        }

        static Node internal(int attribute, double value, Node left, Node right) {
            return new Node(attribute, value, left, right, 0);
        }

        boolean isExternal() {
            return left == null;
        }
    }

    private final Node root;

    private IsolationTree(Node root) {
        this.root = root;
    }

    /**
     * @param sample      the subsample this tree is built from; not modified
     * @param heightLimit {@code l} from Algorithm 1
     * @param random      seeded by the forest, so the tree is reproducible
     */
    static IsolationTree build(List<double[]> sample, int heightLimit, Random random) {
        return new IsolationTree(grow(sample, 0, heightLimit, random));
    }

    private static Node grow(List<double[]> sample, int depth, int heightLimit, Random random) {
        if (depth >= heightLimit || sample.size() <= 1) {
            return Node.external(sample.size());
        }

        int attribute = chooseVaryingAttribute(sample, random);
        if (attribute < 0) {
            // Every attribute is constant here: these rows are identical as far
            // as the model can see, and no split separates them.
            return Node.external(sample.size());
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] row : sample) {
            min = Math.min(min, row[attribute]);
            max = Math.max(max, row[attribute]);
        }

        double splitValue = min + random.nextDouble() * (max - min);

        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();
        for (double[] row : sample) {
            if (row[attribute] < splitValue) {
                left.add(row);
            } else {
                right.add(row);
            }
        }

        if (left.isEmpty() || right.isEmpty()) {
            // The draw landed on the minimum. Nothing was separated, so do not
            // spend a level of depth pretending otherwise.
            return Node.external(sample.size());
        }

        return Node.internal(attribute, splitValue,
                grow(left, depth + 1, heightLimit, random),
                grow(right, depth + 1, heightLimit, random));
    }

    /**
     * An attribute chosen uniformly from those that vary in this subsample, or
     * {@code -1} when none do.
     *
     * <p>Compared with {@code >} rather than {@code !=}: floating point equality
     * on accumulated values is exactly the kind of comparison that makes
     * tree-building non-reproducible across platforms, and a range of zero is
     * the only thing that needs detecting here.
     */
    private static int chooseVaryingAttribute(List<double[]> sample, Random random) {
        int dimension = sample.get(0).length;
        List<Integer> varying = new ArrayList<>(dimension);

        for (int attribute = 0; attribute < dimension; attribute++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double[] row : sample) {
                min = Math.min(min, row[attribute]);
                max = Math.max(max, row[attribute]);
            }
            if (max - min > 0.0) {
                varying.add(attribute);
            }
        }

        if (varying.isEmpty()) {
            return -1;
        }
        return varying.get(random.nextInt(varying.size()));
    }

    /**
     * {@code h(x)}: Algorithm 3, PathLength.
     *
     * <p>The number of edges walked, plus {@code c(size)} at the external node
     * reached. That adjustment matters: a node holding twelve points that the
     * height limit stopped from splitting is not the same as one holding a
     * single isolated point at the same depth, and without the correction they
     * would score identically.
     */
    double pathLength(double[] point) {
        Node node = root;
        int edges = 0;
        while (!node.isExternal()) {
            node = point[node.splitAttribute()] < node.splitValue() ? node.left() : node.right();
            edges++;
        }
        return edges + IsolationForest.averagePathLength(node.size());
    }
}
