package com.ledgerguard.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.support.TestIdempotency;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The full loop: a real payment, published through Kafka, settled by the
 * simulator, then deliberately broken and caught by reconciliation.
 *
 * <p>The grace window is set to zero so a run reports immediately rather than
 * waiting; each test still waits for the settlement record to actually arrive
 * before touching it, which is the honest way to handle an asynchronous
 * consumer.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerguard.reconciliation.grace-seconds=0")
@Testcontainers
class ReconciliationFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TestIdempotency.autoKey(rest);
    }

    // ---------- helpers ----------

    private UUID createAccount(String name) {
        ResponseEntity<JsonNode> r = rest.postForEntity(
                "/accounts", Map.of("name", name, "currency", "USD"), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(r.getBody().get("id").asText());
    }

    /** A payment, settled by the simulator, ready to be broken. Returns the transaction id. */
    private UUID settledPayment(String label, String amount) {
        UUID payer = createAccount(label + " Payer");
        UUID payee = createAccount(label + " Payee");

        ResponseEntity<JsonNode> r = rest.postForEntity("/payments", Map.of(
                "sourceAccountId", payer.toString(),
                "destinationAccountId", payee.toString(),
                "amount", amount,
                "currency", "USD",
                "description", label), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID transactionId = UUID.fromString(r.getBody().get("transaction").get("id").asText());

        // The simulator consumes asynchronously; wait for the external world to catch up.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(settlementCount(transactionId)).isEqualTo(1));

        return transactionId;
    }

    private long settlementCount(UUID transactionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_records WHERE external_reference = ?",
                Long.class, transactionId.toString());
    }

    private String injectFault(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> r = rest.exchange("/admin/settlement/faults", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().get("outcome").asText();
    }

    private static Map<String, Object> fault(String type, Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    private JsonNode runReconciliation() {
        ResponseEntity<JsonNode> r = rest.postForEntity("/reconciliation/runs", null, JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private JsonNode incidentsFor(UUID transactionId) {
        return rest.getForEntity("/reconciliation/incidents?transactionId={id}", JsonNode.class, transactionId)
                .getBody();
    }

    /** The single incident for this transaction, asserting there is exactly one. */
    private JsonNode onlyIncidentFor(UUID transactionId) {
        JsonNode incidents = incidentsFor(transactionId);
        assertThat(incidents).as("expected exactly one incident for %s", transactionId).hasSize(1);
        return incidents.get(0);
    }

    // ---------- MATCHED ----------

    @Test
    @DisplayName("a clean payment reconciles as MATCHED and produces no incident")
    void matchedProducesNoIncident() {
        UUID transactionId = settledPayment("Matched", "10.25");

        JsonNode run = runReconciliation();

        assertThat(run.get("matched").asInt())
                .as("at least this transaction agreed")
                .isGreaterThanOrEqualTo(1);
        assertThat(incidentsFor(transactionId))
                .as("agreement is recorded as a count, not a row: an incident per match would bury the real ones")
                .isEmpty();
    }

    // ---------- the five discrepancies ----------

    @Test
    @DisplayName("dropping the external record yields MISSING_SETTLEMENT with only an internal side")
    void missingSettlement() {
        UUID transactionId = settledPayment("Missing", "10.25");
        injectFault(fault("DROP_SETTLEMENT", "transactionId", transactionId.toString()));
        assertThat(settlementCount(transactionId)).isZero();

        runReconciliation();
        JsonNode incident = onlyIncidentFor(transactionId);

        assertThat(incident.get("type").asText()).isEqualTo("MISSING_SETTLEMENT");
        assertThat(incident.get("severity").asText()).isEqualTo("MEDIUM");
        assertThat(incident.get("status").asText()).isEqualTo("OPEN");
        assertThat(incident.get("transactionId").asText()).isEqualTo(transactionId.toString());
        assertThat(incident.get("settlementRecordId").isNull())
                .as("there is no external record to point at")
                .isTrue();
        assertThat(incident.get("internalAmountMinor").asLong()).isEqualTo(1025L);
        assertThat(incident.get("differenceMinor").asLong()).isEqualTo(1025L);
    }

    @Test
    @DisplayName("restating the external amount yields AMOUNT_MISMATCH with both sides as evidence")
    void amountMismatch() {
        UUID transactionId = settledPayment("Amount", "250.00");

        // We recorded $250; the processor now says $200.
        injectFault(fault("RESTATE_AMOUNT",
                "transactionId", transactionId.toString(), "amountDeltaMinor", -5_000));

        runReconciliation();
        JsonNode incident = onlyIncidentFor(transactionId);

        assertThat(incident.get("type").asText()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(incident.get("internalAmountMinor").asLong()).isEqualTo(25_000L);
        assertThat(incident.get("externalAmountMinor").asLong()).isEqualTo(20_000L);
        assertThat(incident.get("differenceMinor").asLong()).isEqualTo(5_000L);
        assertThat(incident.get("severity").asText())
                .as("$50 in question: above the type floor is not warranted yet")
                .isEqualTo("MEDIUM");

        // Evidence points at a settlement record that actually exists.
        UUID recordId = UUID.fromString(incident.get("settlementRecordId").asText());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_records WHERE id = ?", Long.class, recordId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a second external record yields DUPLICATE_SETTLEMENT at HIGH")
    void duplicateSettlement() {
        UUID transactionId = settledPayment("Duplicate", "10.25");
        injectFault(fault("DUPLICATE_SETTLEMENT", "transactionId", transactionId.toString()));
        assertThat(settlementCount(transactionId)).isEqualTo(2);

        runReconciliation();
        JsonNode incident = onlyIncidentFor(transactionId);

        assertThat(incident.get("type").asText()).isEqualTo("DUPLICATE_SETTLEMENT");
        assertThat(incident.get("severity").asText())
                .as("a control failed, regardless of the amount")
                .isEqualTo("HIGH");
        assertThat(incident.get("differenceMinor").asLong())
                .as("the exposure is the extra copy")
                .isEqualTo(1025L);
        assertThat(incident.get("detail").asText()).contains("2 external records");
    }

    @Test
    @DisplayName("a status change with matching amounts yields STATUS_MISMATCH at LOW, still flagged")
    void statusMismatch() {
        UUID transactionId = settledPayment("Status", "10.25");
        injectFault(fault("CHANGE_STATUS",
                "transactionId", transactionId.toString(), "newStatus", "FAILED"));

        runReconciliation();
        JsonNode incident = onlyIncidentFor(transactionId);

        assertThat(incident.get("type").asText()).isEqualTo("STATUS_MISMATCH");
        assertThat(incident.get("severity").asText()).isEqualTo("LOW");
        assertThat(incident.get("differenceMinor").asLong())
                .as("no money is in question, which is exactly why this is easy to miss")
                .isZero();
        assertThat(incident.get("internalStatus").asText()).isEqualTo("POSTED");
        assertThat(incident.get("externalStatus").asText()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a phantom settlement yields UNEXPECTED_EXTERNAL_TRANSACTION with only an external side")
    void unexpectedExternalTransaction() {
        long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_incidents WHERE discrepancy_type = 'UNEXPECTED_EXTERNAL_TRANSACTION'",
                Long.class);

        injectFault(fault("PHANTOM_SETTLEMENT", "amountMinor", 9_900, "currency", "USD"));
        runReconciliation();

        long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_incidents WHERE discrepancy_type = 'UNEXPECTED_EXTERNAL_TRANSACTION'",
                Long.class);
        assertThat(after).isEqualTo(before + 1);

        Map<String, Object> incident = jdbc.queryForMap(
                "SELECT * FROM reconciliation_incidents WHERE discrepancy_type = 'UNEXPECTED_EXTERNAL_TRANSACTION' "
                        + "ORDER BY created_at DESC LIMIT 1");

        assertThat(incident.get("transaction_id")).as("no internal side exists").isNull();
        assertThat(incident.get("settlement_record_id")).isNotNull();
        assertThat(incident.get("severity"))
                .as("money moved that the ledger never authorised")
                .isEqualTo("HIGH");
        assertThat((Long) incident.get("external_amount_minor")).isEqualTo(9_900L);
    }

    // ---------- repeated runs ----------

    @Test
    @DisplayName("running twice does not file the same standing discrepancy twice")
    void repeatedRunsDoNotDuplicateIncidents() {
        UUID transactionId = settledPayment("Repeat", "10.25");
        injectFault(fault("RESTATE_AMOUNT",
                "transactionId", transactionId.toString(), "amountDeltaMinor", -300));

        JsonNode firstRun = runReconciliation();
        assertThat(firstRun.get("newIncidents").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(incidentsFor(transactionId)).hasSize(1);

        // The disagreement is still there, and still nobody's fixed it.
        JsonNode secondRun = runReconciliation();

        assertThat(incidentsFor(transactionId))
                .as("a standing problem is one incident, not one per run")
                .hasSize(1);
        assertThat(secondRun.get("alreadyOpen").asInt())
                .as("the second run still finds it, it just does not file it again")
                .isGreaterThanOrEqualTo(1);

        // Once resolved, a later run is free to raise it again: the discrepancy
        // is real and nobody is looking at it any more.
        UUID incidentId = UUID.fromString(incidentsFor(transactionId).get(0).get("id").asText());
        rest.postForEntity("/reconciliation/incidents/{id}/resolve", null, JsonNode.class, incidentId);

        runReconciliation();
        assertThat(incidentsFor(transactionId))
                .as("one resolved, one freshly raised")
                .hasSize(2);
        assertThat(rest.getForEntity(
                "/reconciliation/incidents?transactionId={id}&status=OPEN", JsonNode.class, transactionId)
                .getBody()).hasSize(1);
    }

    // ---------- triage ----------

    @Test
    @DisplayName("incidents can be filtered by type, severity and status, and resolved")
    void incidentsAreFilterableAndResolvable() {
        UUID transactionId = settledPayment("Triage", "10.25");
        injectFault(fault("CHANGE_STATUS",
                "transactionId", transactionId.toString(), "newStatus", "PENDING"));
        runReconciliation();

        UUID incidentId = UUID.fromString(onlyIncidentFor(transactionId).get("id").asText());

        assertThat(rest.getForEntity(
                "/reconciliation/incidents?type=STATUS_MISMATCH&severity=LOW&status=OPEN", JsonNode.class)
                .getBody()).isNotEmpty();

        ResponseEntity<JsonNode> resolved = rest.postForEntity(
                "/reconciliation/incidents/{id}/resolve", null, JsonNode.class, incidentId);

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.getBody().get("resolvedAt").isNull())
                .as("a resolved incident must record when, enforced by a database CHECK")
                .isFalse();

        assertThat(rest.getForEntity(
                "/reconciliation/incidents?transactionId={id}&status=OPEN", JsonNode.class, transactionId)
                .getBody()).isEmpty();
    }

    // ---------- earlier phases ----------

    @Test
    @DisplayName("Phase 1 to 4 guarantees still hold with reconciliation added")
    void earlierGuaranteesSurvive() {
        UUID transactionId = settledPayment("Regression", "10.00");

        // Phase 1: nothing unbalanced anywhere.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT transaction_id FROM postings GROUP BY transaction_id "
                        + "HAVING SUM(CASE WHEN type='DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0) t",
                Long.class)).isZero();

        // Phase 2: postings are untouched by anything reconciliation does.
        long postingsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM postings WHERE transaction_id = ?", Long.class, transactionId);
        injectFault(fault("RESTATE_AMOUNT",
                "transactionId", transactionId.toString(), "amountDeltaMinor", -100));
        runReconciliation();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM postings WHERE transaction_id = ?", Long.class, transactionId))
                .as("breaking the external world must not touch the ledger")
                .isEqualTo(postingsBefore);

        // Phase 3: idempotent replay still works.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "recon-replay-" + UUID.randomUUID());
        UUID payer = createAccount("Recon Replay Payer");
        UUID payee = createAccount("Recon Replay Payee");
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

        // Phase 4: every outbox event still gets published.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Long.class)).isZero());
    }
}
