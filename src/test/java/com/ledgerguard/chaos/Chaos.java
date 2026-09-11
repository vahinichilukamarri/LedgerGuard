package com.ledgerguard.chaos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The single source of randomness in ChaosLab, and the reason every scenario is
 * reproducible.
 *
 * <h2>Seeded, never random</h2>
 *
 * A chaos test that fails once and then passes is worse than no chaos test: it
 * teaches the team to re-run the build instead of reading the failure. So every
 * choice a scenario makes — which event in a batch the broker rejects, what
 * order messages arrive in, which duplicates land where — comes from here, and
 * here is seeded with a constant the scenario declares.
 *
 * <p>The seed is deliberately part of the test, not generated per run. A
 * scenario that fails fails for everyone, on every machine, forever, until it is
 * fixed. Exploring the space with fresh randomness is what Phase 6's property
 * tests are for; this phase is about specific failures being permanently pinned.
 *
 * <p>Keeping it this small is intentional. It is the determinism source, not a
 * framework: the faults themselves live in {@link ChaosKafkaTemplate} and
 * {@link ChaosDataSource}, which need no randomness at all.
 */
public final class Chaos {

    private final long seed;
    private final Random random;

    private Chaos(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public static Chaos of(long seed) {
        return new Chaos(seed);
    }

    /** A deterministic choice in {@code [0, bound)}. */
    public int pick(int bound) {
        return random.nextInt(bound);
    }

    /** A deterministic choice in {@code [origin, bound]}, both inclusive. */
    public int pickBetween(int origin, int bound) {
        return origin + random.nextInt(bound - origin + 1);
    }

    /** A deterministic reordering. The input is not modified. */
    public <T> List<T> shuffle(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        Collections.shuffle(copy, random);
        return copy;
    }

    /**
     * The seed, for a failure message. Printing it is what lets someone reading
     * a CI log reproduce the exact run locally without guessing.
     */
    public long seed() {
        return seed;
    }

    @Override
    public String toString() {
        return "Chaos(seed=" + seed + ")";
    }
}
