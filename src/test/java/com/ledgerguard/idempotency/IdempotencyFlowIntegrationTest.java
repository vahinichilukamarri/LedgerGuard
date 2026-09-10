package com.ledgerguard.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.support.LedgerPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 against a real PostgreSQL, because the concurrency guarantee is a
 * property of a UNIQUE index and cannot be tested against a fake.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // No Kafka container here. These tests are about the ledger, not
                // delivery, and leaving the publisher on would make every poll block
                // on an unreachable broker. Outbox rows are still written; nothing
                // drains them, which is exactly the Kafka-is-down state.
                "ledgerguard.outbox.publisher.enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
@Testcontainers
class IdempotencyFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = LedgerPostgres.newContainer();

    /** Enough threads to make the race real without making the suite slow. */
    private static final int CONCURRENT_ATTEMPTS = 20;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- helpers ----------

    private UUID createAccount(String name) {
        HttpHeaders headers = jsonHeaders(UUID.randomUUID().toString());
        ResponseEntity<JsonNode> r = rest.exchange("/accounts", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "currency", "USD"), headers), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(r.getBody().get("id").asText());
    }

    private static HttpHeaders jsonHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private Map<String, Object> paymentBody(UUID from, UUID to, String amount) {
        return Map.of(
                "sourceAccountId", from.toString(),
                "destinationAccountId", to.toString(),
                "amount", amount,
                "currency", "USD",
                "description", "idempotent payment");
    }

    private ResponseEntity<String> postPayment(String key, Map<String, Object> body) {
        return rest.exchange("/payments", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(key)), String.class);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---------- the header is required ----------

    @Test
    @DisplayName("a write without an Idempotency-Key is refused")
    void missingKeyIsRefused() {
        UUID payer = createAccount("NoKey Payer");
        UUID payee = createAccount("NoKey Payee");

        long paymentsBefore = count("SELECT COUNT(*) FROM payments");

        ResponseEntity<String> response = postPayment(null, paymentBody(payer, payee, "1.00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("idempotency_key_required");
        assertThat(count("SELECT COUNT(*) FROM payments")).isEqualTo(paymentsBefore);
    }

    // ---------- replay ----------

    @Test
    @DisplayName("the same key with the same body replays the original response byte for byte")
    void sameKeySameBodyReplays() {
        UUID payer = createAccount("Replay Payer");
        UUID payee = createAccount("Replay Payee");
        Map<String, Object> body = paymentBody(payer, payee, "10.25");
        String key = UUID.randomUUID().toString();

        // Relative counts: every test in this class shares one database.
        long paymentsBefore = count("SELECT COUNT(*) FROM payments");
        long transactionsBefore = count("SELECT COUNT(*) FROM transactions");
        long postingsBefore = count("SELECT COUNT(*) FROM postings");

        ResponseEntity<String> first = postPayment(key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst(IdempotencyService.REPLAY_HEADER)).isNull();

        ResponseEntity<String> second = postPayment(key, body);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody())
                .as("a replay must be byte-identical, ids included")
                .isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst(IdempotencyService.REPLAY_HEADER))
                .as("a replay is signalled in a header, so the body stays identical")
                .isEqualTo("true");

        // The money moved exactly once.
        assertThat(count("SELECT COUNT(*) FROM payments") - paymentsBefore).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM transactions") - transactionsBefore).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM postings") - postingsBefore).isEqualTo(2);
    }

    @Test
    @DisplayName("key order in the retried body does not defeat the replay")
    void reorderedBodyStillReplays() {
        UUID payer = createAccount("Reorder Payer");
        UUID payee = createAccount("Reorder Payee");
        String key = UUID.randomUUID().toString();

        // Same fields, different insertion order.
        Map<String, Object> first = new java.util.LinkedHashMap<>();
        first.put("sourceAccountId", payer.toString());
        first.put("destinationAccountId", payee.toString());
        first.put("amount", "3.00");
        first.put("currency", "USD");

        Map<String, Object> reordered = new java.util.LinkedHashMap<>();
        reordered.put("currency", "USD");
        reordered.put("amount", "3.00");
        reordered.put("destinationAccountId", payee.toString());
        reordered.put("sourceAccountId", payer.toString());

        ResponseEntity<String> a = postPayment(key, first);
        ResponseEntity<String> b = postPayment(key, reordered);

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getBody()).isEqualTo(a.getBody());
        assertThat(b.getHeaders().getFirst(IdempotencyService.REPLAY_HEADER)).isEqualTo("true");
    }

    // ---------- conflict ----------

    @Test
    @DisplayName("the same key with a different body is a 409 and changes nothing")
    void sameKeyDifferentBodyConflicts() {
        UUID payer = createAccount("Conflict Payer");
        UUID payee = createAccount("Conflict Payee");
        String key = UUID.randomUUID().toString();

        long paymentsBefore = count("SELECT COUNT(*) FROM payments");
        long postingsBefore = count("SELECT COUNT(*) FROM postings");

        assertThat(postPayment(key, paymentBody(payer, payee, "10.25")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> conflicting = postPayment(key, paymentBody(payer, payee, "99.99"));

        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflicting.getBody()).contains("idempotency_key_conflict");

        assertThat(count("SELECT COUNT(*) FROM payments") - paymentsBefore)
                .as("the conflicting request must not have created anything")
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM postings") - postingsBefore).isEqualTo(2);
    }

    @Test
    @DisplayName("keys are scoped per endpoint, so the same token elsewhere is unrelated")
    void keysAreScopedPerEndpoint() {
        UUID payer = createAccount("Scoped Payer");
        UUID payee = createAccount("Scoped Payee");
        String sharedToken = UUID.randomUUID().toString();

        ResponseEntity<String> payment = postPayment(sharedToken, paymentBody(payer, payee, "5.00"));
        assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID paymentId = UUID.fromString(readJson(payment.getBody()).get("paymentId").asText());

        // The very same token, on the refunds endpoint, is a different key.
        ResponseEntity<String> refund = rest.exchange("/payments/{id}/refunds", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", "1.00"), jsonHeaders(sharedToken)),
                String.class, paymentId);

        assertThat(refund.getStatusCode())
                .as("scoping by endpoint means this is not a conflict")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id = ?", paymentId)).isEqualTo(1);
    }

    // ---------- refunds and reversals ----------

    @Test
    @DisplayName("a retried refund replays instead of refunding twice")
    void refundReplays() {
        UUID payer = createAccount("RefundKey Payer");
        UUID payee = createAccount("RefundKey Payee");
        UUID paymentId = UUID.fromString(readJson(
                postPayment(UUID.randomUUID().toString(), paymentBody(payer, payee, "10.00")).getBody())
                .get("paymentId").asText());

        String key = UUID.randomUUID().toString();
        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(Map.of("amount", "4.00"), jsonHeaders(key));

        ResponseEntity<String> first =
                rest.exchange("/payments/{id}/refunds", HttpMethod.POST, request, String.class, paymentId);
        ResponseEntity<String> retry =
                rest.exchange("/payments/{id}/refunds", HttpMethod.POST, request, String.class, paymentId);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(retry.getHeaders().getFirst(IdempotencyService.REPLAY_HEADER)).isEqualTo("true");

        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id = ?", paymentId)).isEqualTo(1);
        assertThat(count("SELECT COALESCE(SUM(amount_minor),0) FROM refunds WHERE payment_id = ?", paymentId))
                .as("refunded once, not twice")
                .isEqualTo(400L);
    }

    @Test
    @DisplayName("a retried reversal replays the 201 instead of returning already-reversed")
    void reversalReplays() {
        UUID payer = createAccount("ReversalKey Payer");
        UUID payee = createAccount("ReversalKey Payee");
        UUID txn = UUID.fromString(readJson(
                postPayment(UUID.randomUUID().toString(), paymentBody(payer, payee, "8.00")).getBody())
                .get("transaction").get("id").asText());

        String key = UUID.randomUUID().toString();
        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(Map.of("description", "reversing"), jsonHeaders(key));

        ResponseEntity<String> first =
                rest.exchange("/transactions/{id}/reversals", HttpMethod.POST, request, String.class, txn);
        ResponseEntity<String> retry =
                rest.exchange("/transactions/{id}/reversals", HttpMethod.POST, request, String.class, txn);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode())
                .as("without a key this retry would be a confusing 422 on a request that actually worked")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(count("SELECT COUNT(*) FROM reversals WHERE original_transaction_id = ?", txn))
                .isEqualTo(1);
    }

    // ---------- concurrency ----------

    @Test
    @DisplayName("many simultaneous requests with one key produce exactly one payment")
    void concurrentDuplicatesProduceExactlyOne() throws Exception {
        UUID payer = createAccount("Race Payer");
        UUID payee = createAccount("Race Payee");
        Map<String, Object> body = paymentBody(payer, payee, "12.34");
        String key = UUID.randomUUID().toString();

        long paymentsBefore = count("SELECT COUNT(*) FROM payments");

        // A latch so the threads are genuinely released together rather than
        // trickling out as the pool spins up. Without this the test would be
        // sequential wearing a concurrency costume.
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS);
        CountDownLatch releaseAllAtOnce = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_ATTEMPTS; i++) {
                futures.add(pool.submit(() -> {
                    releaseAllAtOnce.await();
                    return postPayment(key, body);
                }));
            }
            releaseAllAtOnce.countDown();

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : futures) {
                responses.add(f.get(60, TimeUnit.SECONDS));
            }

            // Exactly one financial effect. Not two, and not zero.
            assertThat(count("SELECT COUNT(*) FROM payments") - paymentsBefore)
                    .as("%d simultaneous identical requests must create one payment", CONCURRENT_ATTEMPTS)
                    .isEqualTo(1);

            // Every caller got the same answer, and every one of them succeeded.
            List<String> bodies = responses.stream().map(ResponseEntity::getBody).distinct().toList();
            assertThat(bodies).as("all callers must see one identical response").hasSize(1);
            assertThat(responses).allSatisfy(r ->
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));

            // Exactly one of them did the work; the rest replayed.
            long replays = responses.stream()
                    .filter(r -> "true".equals(r.getHeaders().getFirst(IdempotencyService.REPLAY_HEADER)))
                    .count();
            assertThat(replays).isEqualTo(CONCURRENT_ATTEMPTS - 1);

            // One key row, and the ledger is still whole.
            assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", key))
                    .isEqualTo(1);
            assertThat(count(
                    "SELECT COALESCE(SUM(CASE WHEN type='DEBIT' THEN amount_minor ELSE -amount_minor END),0) "
                            + "FROM postings"))
                    .as("ledger drift must be zero")
                    .isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- atomicity ----------

    @Test
    @DisplayName("a key is never recorded when the ledger write it belongs to fails")
    void failedWriteLeavesNoKeyBehind() {
        UUID payer = createAccount("Atomic Key Payer");
        String key = UUID.randomUUID().toString();

        // Fails inside the handler, after the idempotency row has been inserted
        // and flushed: the destination does not exist.
        ResponseEntity<String> failed =
                postPayment(key, paymentBody(payer, UUID.randomUUID(), "5.00"));
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", key))
                .as("a key recorded without its transaction would poison every future retry")
                .isZero();

        // Proof it was truly not recorded: the same key now works for a
        // different request, rather than coming back as a 409.
        UUID payee = createAccount("Atomic Key Payee");
        ResponseEntity<String> retry = postPayment(key, paymentBody(payer, payee, "5.00"));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Phase 1 and 2 guarantees still hold with idempotency in front of them")
    void earlierGuaranteesSurvive() {
        UUID payer = createAccount("Regression Payer");
        UUID payee = createAccount("Regression Payee");
        UUID paymentId = UUID.fromString(readJson(
                postPayment(UUID.randomUUID().toString(), paymentBody(payer, payee, "10.00")).getBody())
                .get("paymentId").asText());

        // Refund cap still enforced (distinct key, so this is a real request).
        ResponseEntity<String> tooMuch = rest.exchange("/payments/{id}/refunds", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", "10.01"), jsonHeaders(UUID.randomUUID().toString())),
                String.class, paymentId);
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooMuch.getBody()).contains("refund_amount_exceeded");

        // Postings still immutable: no UPDATE path exists to try.
        assertThat(count("SELECT COUNT(*) FROM postings")).isPositive();

        // Reverse-once still enforced.
        UUID txn = UUID.fromString(readJson(
                postPayment(UUID.randomUUID().toString(), paymentBody(payer, payee, "2.00")).getBody())
                .get("transaction").get("id").asText());

        assertThat(rest.exchange("/transactions/{id}/reversals", HttpMethod.POST,
                new HttpEntity<>(Map.of(), jsonHeaders(UUID.randomUUID().toString())),
                String.class, txn).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> secondReversal = rest.exchange("/transactions/{id}/reversals",
                HttpMethod.POST, new HttpEntity<>(Map.of(), jsonHeaders(UUID.randomUUID().toString())),
                String.class, txn);
        assertThat(secondReversal.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(secondReversal.getBody()).contains("transaction_already_reversed");
    }

    private JsonNode readJson(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + body, e);
        }
    }
}
