package com.ledgerguard.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.support.LedgerPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /reconciliation/runs: run history for the console, independent of the
 * incidents a run produced. No Kafka container here — these runs see whatever
 * the ledger and settlement tables already hold, which for this test is
 * nothing, so each run is trivially empty and the point being tested is the
 * listing itself, not what a run finds.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ledgerguard.outbox.publisher.enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
@Testcontainers
class ReconciliationRunHistoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = LedgerPostgres.newContainer();

    @Autowired
    private TestRestTemplate rest;

    private UUID triggerRun() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/reconciliation/runs", null, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("runId").asText());
    }

    @Test
    @DisplayName("GET /reconciliation/runs lists triggered runs, newest first, with paging metadata")
    void listsRunsNewestFirst() {
        UUID first = triggerRun();
        UUID second = triggerRun();

        ResponseEntity<JsonNode> response = rest.getForEntity("/reconciliation/runs", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.hasNonNull("totalElements")).isTrue();
        assertThat(body.hasNonNull("totalPages")).isTrue();

        List<String> ids = new ArrayList<>();
        body.get("content").forEach(node -> ids.add(node.get("runId").asText()));
        assertThat(ids).contains(first.toString(), second.toString());
        assertThat(ids.indexOf(second.toString())).isLessThan(ids.indexOf(first.toString()));

        JsonNode newest = body.get("content").get(ids.indexOf(second.toString()));
        assertThat(newest.hasNonNull("startedAt")).isTrue();
        assertThat(newest.hasNonNull("completedAt")).isTrue();
        assertThat(newest.hasNonNull("internalExamined")).isTrue();
        assertThat(newest.hasNonNull("externalExamined")).isTrue();
        assertThat(newest.hasNonNull("matched")).isTrue();
        assertThat(newest.hasNonNull("discrepancies")).isTrue();
    }

    @Test
    @DisplayName("GET /reconciliation/runs?size=1&page=0 bounds the page to one row")
    void respectsPageAndSizeParameters() {
        triggerRun();
        triggerRun();

        ResponseEntity<JsonNode> response =
                rest.getForEntity("/reconciliation/runs?page=0&size=1", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("content")).hasSize(1);
        assertThat(body.get("size").asInt()).isEqualTo(1);
        assertThat(body.get("page").asInt()).isEqualTo(0);
    }
}
