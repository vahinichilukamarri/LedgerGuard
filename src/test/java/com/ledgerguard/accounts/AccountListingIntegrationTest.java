package com.ledgerguard.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.support.LedgerPostgres;
import com.ledgerguard.support.TestIdempotency;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /accounts: a plain offset-paginated listing, filtered by nothing, sorted newest first. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ledgerguard.outbox.publisher.enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
@Testcontainers
class AccountListingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = LedgerPostgres.newContainer();

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void attachIdempotencyKeys() {
        TestIdempotency.autoKey(rest);
    }

    private UUID createAccount(String name, String currency) {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/accounts", Map.of("name", name, "currency", currency), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").asText());
    }

    @Test
    @DisplayName("GET /accounts returns every created account, newest first, with paging metadata")
    void listsAccountsNewestFirst() {
        UUID first = createAccount("Listing A", "USD");
        UUID second = createAccount("Listing B", "USD");

        ResponseEntity<JsonNode> response = rest.getForEntity("/accounts", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("content")).isNotEmpty();
        assertThat(body.hasNonNull("page")).isTrue();
        assertThat(body.hasNonNull("size")).isTrue();
        assertThat(body.hasNonNull("totalElements")).isTrue();
        assertThat(body.hasNonNull("totalPages")).isTrue();

        java.util.List<String> ids = new java.util.ArrayList<>();
        body.get("content").forEach(node -> ids.add(node.get("id").asText()));
        assertThat(ids).contains(first.toString(), second.toString());

        // Newest first: second was created after first, so it must sort earlier.
        assertThat(ids.indexOf(second.toString())).isLessThan(ids.indexOf(first.toString()));
    }

    @Test
    @DisplayName("GET /accounts?size=1&page=0 bounds the page to one row")
    void respectsPageAndSizeParameters() {
        createAccount("Bounded A", "USD");
        createAccount("Bounded B", "USD");

        ResponseEntity<JsonNode> response =
                rest.getForEntity("/accounts?page=0&size=1", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("content")).hasSize(1);
        assertThat(body.get("size").asInt()).isEqualTo(1);
        assertThat(body.get("page").asInt()).isEqualTo(0);
    }
}
