package com.ledgerguard.detection.explain.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Narratives already generated, keyed by the evidence that produced them.
 *
 * <h2>This is the determinism mechanism, and it is honest about its limits</h2>
 *
 * Phases 7 through 10 could promise that the same ledger produced the same
 * output. This phase cannot: hosted inference is not reproducible even at
 * temperature 0, because batching, kernel scheduling and silent weight updates
 * behind a stable model id all vary underneath you. Saying "temperature 0" and
 * calling it deterministic would be the kind of claim this project spends most
 * of its documentation avoiding.
 *
 * <p>What a cache buys instead is narrower and real: <b>the same evidence
 * returns the same words for as long as the entry lives.</b> A reviewer
 * refreshing an account does not watch the explanation subtly rewrite itself,
 * and two people discussing one account are reading the same sentences. That
 * is most of what determinism was protecting here, and it is achievable.
 *
 * <h2>The key</h2>
 *
 * {@code SHA-256(model | promptVersion | evidence JSON)}.
 *
 * <p>Everything that can change the words is in it. The model id, because a
 * different model writes differently; the prompt version, because editing the
 * instructions without invalidating the cache would serve old prose as if the
 * new prompt had produced it; and the evidence, because that is the content.
 *
 * <p><b>{@code asOf} is deliberately not in it</b>, and nor is the account id.
 * The narrative describes numbers, so two requests a second apart that produce
 * identical numbers should produce identical prose rather than paying twice for
 * the privilege of differing. The flip side is the property that matters most:
 * any change to any number the narrative could mention changes the hash, so
 * stale prose about moved numbers is not representable.
 *
 * <h2>In memory, bounded, lost on restart</h2>
 *
 * The same posture as Phase 9's model, for the same reason: a persisted cache
 * of generated text is a second store that can disagree with the ledger, and
 * the cost of missing it is one API call. Eviction is least-recently-used, so
 * the accounts a team is actively working stay warm.
 */
public class NarrativeCache {

    private final Map<String, String> entries;

    public NarrativeCache(int maxEntries) {
        int capacity = Math.max(1, maxEntries);
        this.entries = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > capacity;
                    }
                });
    }

    public static String keyFor(String model, String promptVersion, String evidenceJson) {
        return sha256(model + "|" + promptVersion + "|" + evidenceJson);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public void put(String key, String narrative) {
        entries.put(key, narrative);
    }

    public int size() {
        return entries.size();
    }

    /** Mainly so a test can assert a miss follows an eviction. */
    public void clear() {
        entries.clear();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM. If it is missing, the problem is
            // not one a cache should paper over.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
