package com.ledgerguard.detection.ml;

import java.time.Instant;
import java.util.List;

/**
 * Everything needed to say which model produced a score.
 *
 * <h2>Why this is not optional detail</h2>
 *
 * A statistical signal explains itself: its number can be recomputed from the
 * ledger by anyone who reads the formula. A forest score cannot. The only way to
 * make one reproducible is to record exactly what produced it — the seed, the
 * shape of the forest, the data it saw and when — so that a score can be
 * regenerated later rather than merely believed.
 *
 * <p>Without this, "account X scored 0.71" is an assertion with no provenance,
 * and six weeks later nobody can tell whether the model that said it was the one
 * trained on a quiet Tuesday or the one trained mid-incident.
 *
 * @param seed               the master seed; with the training data this fixes the forest entirely
 * @param trainingSampleSize how many accounts the forest was trained on
 * @param trainedAsOf        the instant the training snapshot was taken, which is
 *                           not the same as when training ran, and is the one
 *                           that determines what the features saw
 * @param featureNames       in index order, so a stored score can be interpreted
 *                           even after the feature set changes
 */
public record ModelMetadata(
        long seed,
        int treeCount,
        int subSampleSize,
        int trainingSampleSize,
        Instant trainedAt,
        Instant trainedAsOf,
        List<String> featureNames) {

    public ModelMetadata {
        featureNames = List.copyOf(featureNames);
    }
}
