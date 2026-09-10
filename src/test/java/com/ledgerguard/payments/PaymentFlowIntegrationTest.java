package com.ledgerguard.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.PostingRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the Phase 1 flow against a real PostgreSQL, with the real
 * Flyway migrations applied. No in-memory database stand-in: the invariant and
 * the schema constraints are the product, so they get tested on the engine that
 * will actually run them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PostingRepository postings;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID createAccount(String name, String currency) {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/accounts", Map.of("name", name, "currency", currency), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").asText());
    }

    private long balanceMinorUnitsOf(UUID accountId) {
        ResponseEntity<JsonNode> response =
                rest.getForEntity("/accounts/{id}/balance", JsonNode.class, accountId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("balanceMinorUnits").asLong();
    }

    @Test
    @DisplayName("POST /payments writes a balanced pair and GET /accounts/{id}/balance reflects it")
    void paymentProducesBalancedPostingsAndDerivedBalances() {
        UUID payer = createAccount("Payer", "USD");
        UUID payee = createAccount("Payee", "USD");

        assertThat(balanceMinorUnitsOf(payer)).isZero();
        assertThat(balanceMinorUnitsOf(payee)).isZero();

        ResponseEntity<JsonNode> response = rest.postForEntity("/payments", Map.of(
                "sourceAccountId", payer.toString(),
                "destinationAccountId", payee.toString(),
                "amount", "10.25",
                "currency", "USD",
                "description", "invoice 42"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body.get("status").asText()).isEqualTo("POSTED");

        JsonNode transaction = body.get("transaction");
        assertThat(transaction.get("currency").asText()).isEqualTo("USD");
        assertThat(transaction.get("description").asText()).isEqualTo("invoice 42");

        JsonNode legs = transaction.get("postings");
        assertThat(legs).hasSize(2);

        // $10.25 must have landed as 1025 minor units, not 10.25 of anything.
        JsonNode credit = legOfType(legs, "CREDIT");
        JsonNode debit = legOfType(legs, "DEBIT");
        assertThat(credit.get("amountMinorUnits").asLong()).isEqualTo(1025L);
        assertThat(debit.get("amountMinorUnits").asLong()).isEqualTo(1025L);
        assertThat(credit.get("accountId").asText()).isEqualTo(payer.toString());
        assertThat(debit.get("accountId").asText()).isEqualTo(payee.toString());

        // The decimal rendering at the boundary is exact.
        assertThat(new BigDecimal(debit.get("amount").asText())).isEqualByComparingTo("10.25");

        // Sum of debits equals sum of credits for this transaction.
        UUID transactionId = UUID.fromString(transaction.get("id").asText());
        long net = postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(transactionId).stream()
                .mapToLong(Posting::signedAmountMinor)
                .sum();
        assertThat(net).as("stored postings must net to zero").isZero();

        // Balances are derived from those postings, with no stored balance column.
        assertThat(balanceMinorUnitsOf(payer)).isEqualTo(-1025L);
        assertThat(balanceMinorUnitsOf(payee)).isEqualTo(1025L);

        ResponseEntity<JsonNode> payeeBalance =
                rest.getForEntity("/accounts/{id}/balance", JsonNode.class, payee);
        assertThat(new BigDecimal(payeeBalance.getBody().get("balance").asText()))
                .isEqualByComparingTo("10.25");
    }

    @Test
    @DisplayName("balances accumulate across payments and stay derived from postings alone")
    void balancesAccumulateAcrossPayments() {
        UUID payer = createAccount("Repeat Payer", "USD");
        UUID payee = createAccount("Repeat Payee", "USD");

        pay(payer, payee, "10.25");
        pay(payer, payee, "0.75");
        pay(payee, payer, "1.00");

        assertThat(balanceMinorUnitsOf(payer)).isEqualTo(-1000L);
        assertThat(balanceMinorUnitsOf(payee)).isEqualTo(1000L);

        // Whole-ledger check: every posting ever written nets to zero.
        Long ledgerNet = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0) "
                        + "FROM postings", Long.class);
        assertThat(ledgerNet).isZero();
    }

    @Test
    @DisplayName("a persisted posting cannot be updated, even by reflection plus flush")
    void persistedPostingCannotBeMutated() {
        UUID payer = createAccount("Immutable Payer", "USD");
        UUID payee = createAccount("Immutable Payee", "USD");

        JsonNode transaction = pay(payer, payee, "10.25").get("transaction");
        UUID postingId = UUID.fromString(transaction.get("postings").get(0).get("id").asText());

        long before = jdbc.queryForObject(
                "SELECT amount_minor FROM postings WHERE id = ?", Long.class, postingId);
        assertThat(before).isEqualTo(1025L);

        // Force the mutation past the missing setter and ask Hibernate to flush it.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Posting managed = entityManager.find(Posting.class, postingId);
            assertThat(managed).isNotNull();
            try {
                Field amount = Posting.class.getDeclaredField("amountMinor");
                amount.setAccessible(true);
                amount.setLong(managed, 999_999L);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not reach the field to attempt a mutation", e);
            }
            entityManager.flush();
        });

        long after = jdbc.queryForObject(
                "SELECT amount_minor FROM postings WHERE id = ?", Long.class, postingId);
        assertThat(after)
                .as("every posting column is mapped updatable = false, so no UPDATE reaches the row")
                .isEqualTo(1025L);
    }

    @Test
    @DisplayName("an amount finer than the currency minor unit is rejected, never rounded")
    void subMinorUnitAmountIsRejected() {
        UUID payer = createAccount("Precision Payer", "USD");
        UUID payee = createAccount("Precision Payee", "USD");

        ResponseEntity<JsonNode> response = rest.postForEntity("/payments", Map.of(
                "sourceAccountId", payer.toString(),
                "destinationAccountId", payee.toString(),
                "amount", "10.255",
                "currency", "USD"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balanceMinorUnitsOf(payer)).isZero();
        assertThat(balanceMinorUnitsOf(payee)).isZero();
    }

    @Test
    @DisplayName("a payment against an unknown account is a 404 and writes nothing")
    void unknownAccountIsRejected() {
        UUID payer = createAccount("Known Payer", "USD");

        ResponseEntity<JsonNode> response = rest.postForEntity("/payments", Map.of(
                "sourceAccountId", payer.toString(),
                "destinationAccountId", UUID.randomUUID().toString(),
                "amount", "5.00",
                "currency", "USD"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balanceMinorUnitsOf(payer)).isZero();
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

    private static JsonNode legOfType(JsonNode legs, String type) {
        for (JsonNode leg : legs) {
            if (type.equals(leg.get("type").asText())) {
                return leg;
            }
        }
        throw new AssertionError("no " + type + " leg in " + legs);
    }
}
