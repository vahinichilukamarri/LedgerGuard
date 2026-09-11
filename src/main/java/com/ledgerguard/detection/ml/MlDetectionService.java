package com.ledgerguard.detection.ml;

import com.ledgerguard.detection.DetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Trains the forest and scores accounts against it.
 *
 * <h2>Training is explicit, never implicit</h2>
 *
 * There is no lazy training on first request. Scoring without a model returns
 * "no model", and a caller who wants one asks for it. A model that appears as a
 * side effect of the first read would be trained on whatever the ledger happened
 * to contain at that moment, by whoever happened to call first, and nobody would
 * know which snapshot they got.
 *
 * <h2>The population gate</h2>
 *
 * Below {@link #minimumTrainingAccounts} accounts, training is refused rather
 * than performed badly. An Isolation Forest works by finding points that are
 * few and different; on a handful of accounts almost every point is few and
 * different, and the model becomes an elaborate way of reporting that the
 * dataset is small. Serving that quietly would be worse than serving nothing,
 * because a number carries authority that an absence does not.
 *
 * <p>This is the same instinct as Phase 8's three signal states: an absence of
 * evidence must not be able to present itself as evidence of absence.
 *
 * <h2>What is not here</h2>
 *
 * The model lives in memory and does not survive a restart, and nothing
 * retrains it on a schedule. Both are deliberate for this phase and are
 * discussed in ML_DETECTION_REPORT.md under retraining posture.
 */
@Service
public class MlDetectionService {

    private static final Logger log = LoggerFactory.getLogger(MlDetectionService.class);

    private final DetectionService detection;
    private final Clock clock;
    private final long seed;
    private final int treeCount;
    private final int subSampleSize;
    private final int minimumTrainingAccounts;

    /** Null until trained. Volatile because training and scoring arrive on different threads. */
    private volatile TrainedModel model;

    private record TrainedModel(IsolationForest forest, ModelMetadata metadata) {
    }

    public MlDetectionService(
            DetectionService detection,
            Clock clock,
            @Value("${ledgerguard.detection.ml.seed:20260911}") long seed,
            @Value("${ledgerguard.detection.ml.trees:150}") int treeCount,
            @Value("${ledgerguard.detection.ml.subsample-size:256}") int subSampleSize,
            @Value("${ledgerguard.detection.ml.minimum-training-accounts:32}") int minimumTrainingAccounts) {
        this.detection = detection;
        this.clock = clock;
        this.seed = seed;
        this.treeCount = treeCount;
        this.subSampleSize = subSampleSize;
        this.minimumTrainingAccounts = minimumTrainingAccounts;
    }

    /**
     * Train on every account's activity as of {@code asOf}.
     *
     * <p>Unsupervised, which is native to the algorithm rather than a
     * concession: an Isolation Forest never sees labels even when they exist. It
     * does however assume the training population is <em>mostly</em> normal, and
     * nothing here can check that assumption. If the ledger contains many
     * anomalous accounts they become part of what the model considers ordinary,
     * and without labels there is no way to detect that it has happened.
     *
     * @throws InsufficientTrainingDataException when the population is too small
     *                                           to learn anything from
     */
    public ModelMetadata train(Instant asOf) {
        List<DetectionService.ScoredAccount> population = detection.assessAll(asOf);

        if (population.size() < minimumTrainingAccounts) {
            throw new InsufficientTrainingDataException(population.size(), minimumTrainingAccounts);
        }

        List<double[]> rows = new ArrayList<>(population.size());
        for (DetectionService.ScoredAccount account : population) {
            rows.add(FeatureExtractor.extract(account.activity(), account.score()).toArray());
        }

        IsolationForest forest = IsolationForest.train(rows, seed, treeCount, subSampleSize);
        ModelMetadata metadata = new ModelMetadata(
                seed,
                forest.treeCount(),
                forest.subSampleSize(),
                rows.size(),
                Instant.now(clock),
                asOf,
                List.of(FeatureVector.NAMES));

        this.model = new TrainedModel(forest, metadata);
        log.info("detection: trained isolation forest on {} accounts, seed {}, {} trees",
                rows.size(), seed, forest.treeCount());
        return metadata;
    }

    public ModelMetadata train() {
        return train(Instant.now(clock));
    }

    /**
     * This account's isolation score, or empty when no model has been trained.
     *
     * <p>Empty is not an error and not a zero. It means the question has not been
     * asked of a model yet, which the caller must be able to tell apart from a
     * model having looked and found nothing unusual.
     */
    public Optional<Double> score(UUID accountId, Instant asOf) {
        TrainedModel current = model;
        if (current == null) {
            return Optional.empty();
        }
        DetectionService.ScoredAccount assessed = detection.assess(accountId, asOf);
        return Optional.of(current.forest().score(
                FeatureExtractor.extract(assessed.activity(), assessed.score())));
    }

    /** Score an already-assessed account, avoiding a second pass over the ledger. */
    public Optional<Double> score(DetectionService.ScoredAccount assessed) {
        TrainedModel current = model;
        if (current == null) {
            return Optional.empty();
        }
        return Optional.of(current.forest().score(
                FeatureExtractor.extract(assessed.activity(), assessed.score())));
    }

    public Optional<ModelMetadata> metadata() {
        TrainedModel current = model;
        return current == null ? Optional.empty() : Optional.of(current.metadata());
    }

    public boolean isTrained() {
        return model != null;
    }

    public int minimumTrainingAccounts() {
        return minimumTrainingAccounts;
    }

    /** Discard the model. Mainly so a test can assert the untrained path honestly. */
    public void forget() {
        this.model = null;
    }

    /** Refusal to train a model that would only describe the size of the dataset. */
    public static class InsufficientTrainingDataException extends RuntimeException {

        private final int available;
        private final int required;

        public InsufficientTrainingDataException(int available, int required) {
            super(("%d accounts is too few to train an isolation forest; %d are required. "
                    + "A forest isolates points that are few and different, and on a population "
                    + "this small almost every point is few and different, so the model would be "
                    + "describing the size of the dataset rather than anything about behaviour.")
                    .formatted(available, required));
            this.available = available;
            this.required = required;
        }

        public int getAvailable() {
            return available;
        }

        public int getRequired() {
            return required;
        }
    }
}
