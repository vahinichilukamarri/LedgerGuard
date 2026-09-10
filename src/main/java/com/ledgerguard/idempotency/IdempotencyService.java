package com.ledgerguard.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a write endpoint safe to retry.
 *
 * <h2>How the concurrency guarantee actually works</h2>
 *
 * The idempotency row is inserted and flushed <b>before</b> the handler runs.
 * That ordering is the whole design:
 *
 * <ol>
 *   <li>Request A inserts the row and flushes. The INSERT is real but
 *       uncommitted.</li>
 *   <li>Request B, carrying the same key, tries the same INSERT and
 *       <b>blocks</b> on the unique index. PostgreSQL will not let it proceed
 *       while A's row is in flight. B has not touched the ledger.</li>
 *   <li>A does its ledger writes, records its response onto the row, commits.</li>
 *   <li>B unblocks and fails with a unique violation. Its transaction rolls
 *       back having written nothing. It then re-reads the committed row and
 *       replays A's exact response.</li>
 * </ol>
 *
 * <p>If A rolls back instead, B's INSERT simply succeeds and B proceeds as the
 * winner. Either way exactly one set of ledger writes commits: not two, and
 * not zero.
 *
 * <p><b>Trade-off.</b> B holds its connection for as long as A's transaction
 * takes. That is fine here, where handlers finish in milliseconds. A
 * long-running handler would want an explicit {@code IN_PROGRESS} row and an
 * immediate 409 telling the client to retry shortly, rather than making it
 * wait. This design buys simplicity — no status state machine, no polling, and
 * no partially-written row is ever visible — at the cost of that assumption.
 */
@Service
public class IdempotencyService {

    /**
     * How long a key stays replayable. Written to {@code expires_at} but never
     * acted on: nothing sweeps expired rows yet. The column exists now because
     * adding it to an already-populated table later would mean backfilling
     * every row.
     */
    private static final Duration RETENTION = Duration.ofHours(24);

    public static final String REPLAY_HEADER = "Idempotent-Replay";

    /** The constraint whose violation means another request won this key. */
    private static final String UNIQUE_CONSTRAINT = "idempotency_keys_unique";

    /** PostgreSQL SQLState for unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final IdempotencyKeyRepository keys;
    private final RequestFingerprint fingerprints;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository keys, RequestFingerprint fingerprints,
                              ObjectMapper mapper, EntityManager entityManager,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.keys = keys;
        this.fingerprints = fingerprints;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Run {@code action} at most once for the given key.
     *
     * @param idempotencyKey  the client's {@code Idempotency-Key} header
     * @param endpoint        route template, which scopes the key
     * @param method          HTTP method, part of the fingerprint
     * @param path            concrete path including ids, part of the fingerprint
     * @param requestBody     parsed request object, or null
     * @param action          the handler; runs only if this key is unused
     * @return the handler's response, or a byte-identical replay of the first one
     */
    public ResponseEntity<String> execute(String idempotencyKey, String endpoint, String method,
                                          String path, Object requestBody,
                                          Supplier<ResponseEntity<?>> action) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        String key = idempotencyKey.trim();
        String fingerprint = fingerprints.of(method, path, requestBody);

        // Fast path: already answered. Not the authority on races, just a way to
        // avoid starting work that is certain to lose.
        Optional<ResponseEntity<String>> alreadyAnswered = replayIfPresent(endpoint, key, fingerprint);
        if (alreadyAnswered.isPresent()) {
            return alreadyAnswered.get();
        }

        try {
            return transactionTemplate.execute(status -> {
                Instant now = Instant.now(clock);
                keys.save(IdempotencyKey.start(key, endpoint, fingerprint, now, now.plus(RETENTION)));

                // Flush now, not at commit. This is what makes a competing
                // request block here rather than after doing its own ledger
                // writes.
                entityManager.flush();

                ResponseEntity<?> produced = action.get();
                String body = serialize(produced.getBody());

                IdempotencyKey row = keys.findByEndpointAndIdempotencyKey(endpoint, key).orElseThrow();
                row.recordResponse(produced.getStatusCode().value(), body);

                // The same serialized string is both stored and returned, so a
                // replay is byte-identical by construction rather than by hope.
                return ResponseEntity.status(produced.getStatusCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            });
        } catch (RuntimeException e) {
            if (!lostTheRace(e)) {
                throw e;
            }
            // Someone else got there first. Our transaction rolled back without
            // writing to the ledger; their response is now committed.
            return replayIfPresent(endpoint, key, fingerprint)
                    .orElseThrow(() -> new IdempotencyKeyConflictException(key, endpoint));
        }
    }

    /**
     * Did this failure come from another request winning the same key?
     *
     * <p>Deliberately narrow. It matches only a unique violation on
     * {@code idempotency_keys_unique} — SQLState 23505 on that specific
     * constraint. Treating any integrity violation as a lost race would mean
     * silently replaying somebody else's response when the real problem was a
     * ledger constraint, which is a far worse bug than a 500.
     *
     * <p>The check walks the cause chain rather than matching one exception
     * type, because how the violation surfaces depends on where it is raised:
     * a repository call gets Spring's translated
     * {@link DataIntegrityViolationException}, while a direct
     * {@code EntityManager.flush()} does not.
     */
    private static boolean lostTheRace(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hibernate
                    && UNIQUE_CONSTRAINT.equals(hibernate.getConstraintName())) {
                return true;
            }
            if (cause instanceof java.sql.SQLException sql
                    && UNIQUE_VIOLATION.equals(sql.getSQLState())
                    && String.valueOf(sql.getMessage()).contains(UNIQUE_CONSTRAINT)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private Optional<ResponseEntity<String>> replayIfPresent(String endpoint, String key, String fingerprint) {
        return transactionTemplate.execute(status ->
                keys.findByEndpointAndIdempotencyKey(endpoint, key).map(existing -> {
                    if (!existing.matchesFingerprint(fingerprint)) {
                        throw new IdempotencyKeyConflictException(key, endpoint);
                    }
                    if (!existing.hasResponse()) {
                        // Should be unreachable: the response is recorded in the
                        // same transaction that inserts the row.
                        throw new IllegalStateException(
                                "idempotency key " + key + " has no recorded response");
                    }
                    return ResponseEntity.status(HttpStatus.valueOf(existing.getResponseStatus()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(REPLAY_HEADER, "true")
                            .body(existing.getResponseBody());
                }));
    }

    private String serialize(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("response could not be serialized for idempotent replay", e);
        }
    }
}
