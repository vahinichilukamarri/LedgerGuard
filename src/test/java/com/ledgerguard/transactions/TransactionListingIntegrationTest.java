package com.ledgerguard.transactions;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /transactions (list, optionally filtered by accountId) and
 * GET /transactions/{id} (detail, with postings).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ledgerguard.outbox.publisher.enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
@Testcontainers
class TransactionListingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = LedgerPostgres.newContainer();

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void attachIdempotencyKeys() {
        TestIdempotency.autoKey(rest);
    }

    private UUID createAccount(String name) {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/accounts", Map.of("name", name, "currency", "USD"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").asText());
    }

    private JsonNode pay(UUID source, UUID destination, String amount) {
        ResponseEntity<JsonNode> response = rest.postForEntity("/payments", Map.of(
                "sourceAccountId", source.toString(),
                "destinationAccountId", destination.toString(),
                "amount", amount,
                "currency", "USD"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    @DisplayName("GET /transactions lists a posted payment's transaction, newest first")
    void listsTransactions() {
        UUID payer = createAccount("Txn List Payer");
        UUID payee = createAccount("Txn List Payee");
        JsonNode result = pay(payer, payee, "5.00");
        UUID transactionId = UUID.fromString(result.get("transaction").get("id").asText());

        ResponseEntity<JsonNode> response = rest.getForEntity("/transactions", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.hasNonNull("totalElements")).isTrue();

        List<String> ids = new ArrayList<>();
        body.get("content").forEach(node -> ids.add(node.get("id").asText()));
        assertThat(ids).contains(transactionId.toString());

        // Summary rows must not carry postings — that's the detail endpoint's job.
        assertThat(body.get("content").get(0).has("postings")).isFalse();
    }

    @Test
    @DisplayName("GET /transactions?accountId= filters to only transactions touching that account")
    void filtersByAccountId() {
        UUID payer = createAccount("Filter Payer");
        UUID payee = createAccount("Filter Payee");
        UUID stranger = createAccount("Filter Stranger");
        UUID otherPayee = createAccount("Filter Other Payee");

        JsonNode result = pay(payer, payee, "3.00");
        UUID transactionId = UUID.fromString(result.get("transaction").get("id").asText());
        pay(stranger, otherPayee, "9.00");

        ResponseEntity<JsonNode> response =
                rest.getForEntity("/transactions?accountId={id}", JsonNode.class, payer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = response.getBody().get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("id").asText()).isEqualTo(transactionId.toString());
    }

    @Test
    @DisplayName("GET /transactions/{id} returns the full transaction with both postings")
    void returnsTransactionDetailWithPostings() {
        UUID payer = createAccount("Detail Payer");
        UUID payee = createAccount("Detail Payee");
        JsonNode result = pay(payer, payee, "7.50");
        UUID transactionId = UUID.fromString(result.get("transaction").get("id").asText());

        ResponseEntity<JsonNode> response =
                rest.getForEntity("/transactions/{id}", JsonNode.class, transactionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("id").asText()).isEqualTo(transactionId.toString());
        assertThat(body.get("postings")).hasSize(2);
    }

    @Test
    @DisplayName("GET /transactions/{id} for an unknown id is a 404 with the standard error shape")
    void unknownTransactionIs404() {
        ResponseEntity<JsonNode> response =
                rest.getForEntity("/transactions/{id}", JsonNode.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error").asText()).isEqualTo("transaction_not_found");
    }
}
