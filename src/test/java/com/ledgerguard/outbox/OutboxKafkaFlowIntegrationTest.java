package com.ledgerguard.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.consumers.LedgerEventConsumer;
import com.ledgerguard.payments.PaymentService;
import com.ledgerguard.support.TestIdempotency;
import com.ledgerguard.support.LedgerPostgres;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Phase 4 end to end: a real PostgreSQL and a real Kafka broker, both started
 * for the test, consistent with how Postgres has been handled since Phase 1.
 *
 * <p>The outbox guarantee is a property of two systems and their failure modes.
 * It cannot be tested against a mock broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OutboxKafkaFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = LedgerPostgres.newContainer();

    @Container
    @ServiceConnection
    // ConfluentKafkaContainer, not the apache/kafka one: that image's entrypoint
    // formats storage before Testcontainers can inject the mapped port, so
    // advertised.listeners is still 0.0.0.0 and the broker refuses to start.
    // Both are KRaft; docker-compose.yml uses apache/kafka, where the advertised
    // listener is set explicitly and the problem does not arise.
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private LedgerEventConsumer consumer;

    @Autowired
    private ObjectMapper mapper;

    @MockitoSpyBean
    private OutboxEventRepository outboxSpy;

    @BeforeEach
    void setUp() {
        TestIdempotency.autoKey(rest);
        consumer.resetCounters();
    }

    // ---------- helpers ----------

    private UUID createAccount(String name) {
        ResponseEntity<JsonNode> r = rest.postForEntity(
                "/accounts", Map.of("name", name, "currency", "USD"), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(r.getBody().get("id").asText());
    }

    private JsonNode pay(UUID from, UUID to, String amount) {
        ResponseEntity<JsonNode> r = rest.postForEntity("/payments", Map.of(
                "sourceAccountId", from.toString(),
                "destinationAccountId", to.toString(),
                "amount", amount,
                "currency", "USD",
                "description", "outbox test"), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---------- the happy path ----------

    @Test
    @DisplayName("a payment writes exactly one outbox event, which is published and consumed once")
    void paymentProducesOneEventPublishedAndConsumedOnce() {
        UUID payer = createAccount("Outbox Payer");
        UUID payee = createAccount("Outbox Payee");

        JsonNode payment = pay(payer, payee, "10.25");
        UUID paymentId = UUID.fromString(payment.get("paymentId").asText());

        // Exactly one event, written by the same transaction as the payment.
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?", paymentId))
                .isEqualTo(1);

        // The publisher polls on a schedule; nothing had to be triggered by hand.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count(
                        "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND published_at IS NOT NULL",
                        paymentId)).isEqualTo(1));

        UUID eventId = jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID.class, paymentId);

        // And the consumer handled it, exactly once.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", eventId))
                        .isEqualTo(1));

        String payload = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE id = ?", String.class, eventId);
        JsonNode envelope = readTree(payload);

        assertThat(envelope.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("PaymentPosted");
        assertThat(envelope.get("payload").get("amountMinor").asLong()).isEqualTo(1025L);
        assertThat(envelope.get("payload").get("amountMinor").isIntegralNumber()).isTrue();
    }

    @Test
    @DisplayName("refunds and reversals each produce their own event on their own topic")
    void refundAndReversalProduceEvents() {
        UUID payer = createAccount("Event Payer");
        UUID payee = createAccount("Event Payee");

        // Two payments, not one. A payment that has been refunded can no longer
        // be reversed — reversing it as well would return more than was paid —
        // so the reversal here gets its own untouched payment. This test is
        // about each operation emitting its own event on its own topic, not
        // about that particular sequence being legal.
        JsonNode refunded = pay(payer, payee, "10.00");
        UUID refundedPaymentId = UUID.fromString(refunded.get("paymentId").asText());

        JsonNode reversed = pay(payer, payee, "10.00");
        UUID reversedTxn = UUID.fromString(reversed.get("transaction").get("id").asText());

        assertThat(rest.postForEntity("/payments/{id}/refunds", Map.of("amount", "4.00"),
                JsonNode.class, refundedPaymentId).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("/transactions/{id}/reversals", HttpMethod.POST,
                new HttpEntity<>(Map.of("description", "reversing"), headers),
                JsonNode.class, reversedTxn).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                refundedPaymentId, "PaymentRefunded")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                reversedTxn, "TransactionReversed")).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT topic FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                String.class, refundedPaymentId, "PaymentRefunded")).isEqualTo(Topics.REFUNDS);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL")).isZero());
    }

    // ---------- at-least-once, and the consumer absorbing it ----------

    @Test
    @DisplayName("the same event delivered twice is processed once")
    void redeliveredEventIsProcessedOnce() {
        UUID payer = createAccount("Replay Payer");
        UUID payee = createAccount("Replay Payee");
        UUID paymentId = UUID.fromString(pay(payer, payee, "6.00").get("paymentId").asText());

        UUID eventId = jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID.class, paymentId);
        String payload = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE id = ?", String.class, eventId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", eventId))
                        .isEqualTo(1));

        int handledBefore = consumer.handledCount();

        // Exactly what the publisher does after crashing between the send and
        // the mark: the same envelope, same eventId, sent again.
        kafka.send(Topics.PAYMENTS, paymentId.toString(), payload);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(consumer.skippedAsDuplicateCount()).isPositive());

        assertThat(count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", eventId))
                .as("a redelivery must not create a second processed_events row")
                .isEqualTo(1);
        assertThat(consumer.handledCount())
                .as("and must not run the work again")
                .isEqualTo(handledBefore);
    }

    // ---------- atomicity ----------

    @Test
    @DisplayName("a failure after the outbox insert but before commit persists nothing")
    void failureAfterOutboxInsertPersistsNothing() {
        UUID payer = createAccount("Atomic Outbox Payer");
        UUID payee = createAccount("Atomic Outbox Payee");

        long paymentsBefore = count("SELECT COUNT(*) FROM payments");
        long postingsBefore = count("SELECT COUNT(*) FROM postings");
        long outboxBefore = count("SELECT COUNT(*) FROM outbox_events");

        // PaymentService.create() records the outbox event as its last step, so
        // throwing from the surrounding transaction fails strictly after the
        // outbox row has been inserted and before anything commits.
        assertThatThrownBy(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    paymentService.create(payer, payee, 500L, "USD", "doomed");
                    throw new IllegalStateException("simulated failure after the outbox insert");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated failure");

        assertThat(count("SELECT COUNT(*) FROM payments")).isEqualTo(paymentsBefore);
        assertThat(count("SELECT COUNT(*) FROM postings")).isEqualTo(postingsBefore);
        assertThat(count("SELECT COUNT(*) FROM outbox_events"))
                .as("an event announcing a payment that never happened is the bug this pattern exists to prevent")
                .isEqualTo(outboxBefore);
    }

    @Test
    @DisplayName("if the outbox write fails, the payment fails with it")
    void outboxFailureRollsBackThePayment() {
        UUID payer = createAccount("Outbox Fail Payer");
        UUID payee = createAccount("Outbox Fail Payee");

        long paymentsBefore = count("SELECT COUNT(*) FROM payments");
        long postingsBefore = count("SELECT COUNT(*) FROM postings");

        doThrow(new IllegalStateException("simulated outbox failure"))
                .when(outboxSpy).save(any(OutboxEvent.class));
        try {
            ResponseEntity<String> response = rest.postForEntity("/payments", Map.of(
                    "sourceAccountId", payer.toString(),
                    "destinationAccountId", payee.toString(),
                    "amount", "3.00",
                    "currency", "USD"), String.class);

            assertThat(response.getStatusCode().is5xxServerError()).isTrue();
            assertThat(count("SELECT COUNT(*) FROM payments"))
                    .as("money must not move if we cannot record that it moved")
                    .isEqualTo(paymentsBefore);
            assertThat(count("SELECT COUNT(*) FROM postings")).isEqualTo(postingsBefore);
        } finally {
            reset(outboxSpy);
        }
    }

    // ---------- earlier phases still hold ----------

    @Test
    @DisplayName("Phase 1 to 3 guarantees survive the outbox being added")
    void earlierGuaranteesSurvive() {
        UUID payer = createAccount("Regression Payer");
        UUID payee = createAccount("Regression Payee");
        UUID paymentId = UUID.fromString(pay(payer, payee, "10.00").get("paymentId").asText());

        // Phase 1: every transaction still balances, and the ledger nets to zero.
        assertThat(count(
                "SELECT COUNT(*) FROM (SELECT transaction_id FROM postings GROUP BY transaction_id "
                        + "HAVING SUM(CASE WHEN type='DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0) t"))
                .as("no unbalanced transaction anywhere")
                .isZero();

        // Phase 2: the refund cap still bites.
        ResponseEntity<String> tooMuch = rest.postForEntity("/payments/{id}/refunds",
                Map.of("amount", "10.01"), String.class, paymentId);
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // Phase 3: replay still replays, and does not emit a second event.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "outbox-replay-" + UUID.randomUUID());
        Map<String, Object> body = Map.of(
                "sourceAccountId", payer.toString(),
                "destinationAccountId", payee.toString(),
                "amount", "1.00",
                "currency", "USD");

        ResponseEntity<String> first = rest.exchange("/payments", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        ResponseEntity<String> retry = rest.exchange("/payments", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(retry.getBody()).isEqualTo(first.getBody());
        UUID replayedPaymentId = UUID.fromString(readTree(first.getBody()).get("paymentId").asText());
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?", replayedPaymentId))
                .as("a replayed request must not produce a second event")
                .isEqualTo(1);
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + json, e);
        }
    }
}
