package com.ledgerguard.detection.ml;

/**
 * How much one feature did to isolate one point, averaged over the forest.
 *
 * <p>Raw model output: no population context, no ranking, no prose. The
 * explanation layer adds those. Everything here comes from walking the point's
 * actual paths through the actual trees.
 *
 * @param splitsPerTree how many nodes on the point's path split on this feature,
 *                      per tree. A feature with no splits earned no credit and
 *                      did nothing, which is different from having pushed the
 *                      point toward the crowd
 * @param isolationBits {@code log2(n/m)} summed over those nodes and divided by
 *                      the tree count. Always non-negative
 * @param excessBits    {@code isolationBits - splitsPerTree}: the bits earned
 *                      above the one bit an even split would have given at each
 *                      node. <b>The number that matters.</b> Positive means this
 *                      feature isolated the point faster than a coin flip would;
 *                      negative means its splits repeatedly put the point on the
 *                      crowded side, which is evidence of ordinariness on that
 *                      axis rather than an absence of evidence
 */
public record FeatureCredit(
        int index,
        String name,
        double splitsPerTree,
        double isolationBits,
        double excessBits) {
}
