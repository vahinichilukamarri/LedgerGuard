package com.ledgerguard.refunds;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.postings.Posting;
import com.ledgerguard.postings.PostingRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * End-to-end tests for Phase 2 against a real PostgreSQL with the real
 * migrations, consistent with Phase 1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RefundReversalFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PostingRepository postings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RefundService refundService;

    /**
     * A spy, not a mock: every test below uses the real repository. Only the
     * atomicity test stubs it, and it resets afterwards.
     */
    @MockitoSpyBean
    private RefundRepository refundRepositorySpy;

    // ---------- helpers ----------

    private UUID createAccount(String name) {
        ResponseEntity<JsonNode> r = rest.postForEntity(
                "/accounts", Map.of("name", name, "currency", "USD"), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(r.getBody().get("id").asText());
    }

    private JsonNode pay(UUID source, UUID destination, String amount) {
        ResponseEntity<JsonNode> r = rest.postForEntity("/payments", Map.of(
                "sourceAccountId", source.toString(),
                "destinationAccountId", destination.toString(),
                "amount", amount,
                "currency", "USD",
                "description", "original payment"), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private ResponseEntity<JsonNode> refund(UUID paymentId, String amount) {
        return rest.postForEntity("/payments/{id}/refunds",
                Map.of("amount", amount), JsonNode.class, paymentId);
    }

    private ResponseEntity<JsonNode> reverse(UUID transactionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/transactions/{id}/reversals", HttpMethod.POST,
                new HttpEntity<>(Map.of("description", "reversing"), headers),
                JsonNode.class, transactionId);
    }

    private long balanceOf(UUID accountId) {
        return rest.getForEntity("/accounts/{id}/balance", JsonNode.class, accountId)
                .getBody().get("balanceMinorUnits").asLong();
    }

    private long netOfTransaction(UUID transactionId) {
        return postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(transactionId).stream()
                .mapToLong(Posting::signedAmountMinor).sum();
    }

    // ---------- refunds ----------

    @Test
    @DisplayName("payment, partial refund, then over-refunding the remainder is rejected")
    void partialRefundThenOverRefundIsRejected() {
        UUID payer = createAccount("Refund Payer");
        UUID payee = createAccount("Refund Payee");

        JsonNode payment = pay(payer, payee, "10.25");
        UUID paymentId = UUID.fromString(payment.get("paymentId").asText());
        UUID originalTxn = UUID.fromString(payment.get("transaction").get("id").asText());

        assertThat(balanceOf(payer)).isEqualTo(-1025L);
        assertThat(balanceOf(payee)).isEqualTo(1025L);

        // --- partial refund of 4.00, leaving 6.25 refundable ---
        ResponseEntity<JsonNode> partial = refund(paymentId, "4.00");
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = partial.getBody();
        assertThat(body.get("amountMinorUnits").asLong()).isEqualTo(400L);
        assertThat(body.get("refundedTotalMinorUnits").asLong()).isEqualTo(400L);
        assertThat(body.get("remainingRefundableMinorUnits").asLong()).isEqualTo(625L);

        UUID refundTxn = UUID.fromString(body.get("transaction").get("id").asText());
        assertThat(refundTxn).as("a refund is a new transaction").isNotEqualTo(originalTxn);
        assertThat(netOfTransaction(refundTxn)).as("the refund balances on its own").isZero();

        // The original payment postings are untouched by the refund.
        assertThat(postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(originalTxn)).hasSize(2);
        assertThat(netOfTransaction(originalTxn)).isZero();

        // Balances moved by the refund amount, in the opposite direction.
        assertThat(balanceOf(payer)).isEqualTo(-625L);
        assertThat(balanceOf(payee)).isEqualTo(625L);

        // --- refunding more than the remainder is refused ---
        ResponseEntity<JsonNode> tooMuch = refund(paymentId, "6.26");
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooMuch.getBody().get("error").asText()).isEqualTo("refund_amount_exceeded");
        assertThat(tooMuch.getBody().get("message").asText()).contains("625 remain refundable");

        // Nothing changed as a result of the rejection.
        assertThat(balanceOf(payer)).isEqualTo(-625L);
        assertThat(balanceOf(payee)).isEqualTo(625L);
        assertThat(countRefunds(paymentId)).isEqualTo(1);

        // --- refunding exactly the remainder is allowed, and then nothing is ---
        ResponseEntity<JsonNode> rest1 = refund(paymentId, "6.25");
        assertThat(rest1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rest1.getBody().get("remainingRefundableMinorUnits").asLong()).isZero();

        assertThat(balanceOf(payer)).isZero();
        assertThat(balanceOf(payee)).isZero();

        assertThat(refund(paymentId, "0.01").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("refunding an unknown payment is a 404")
    void refundOfUnknownPaymentIsNotFound() {
        assertThat(refund(UUID.randomUUID(), "1.00").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------- reversals ----------

    @Test
    @DisplayName("reversal leaves the original postings untouched and nets the pair to zero")
    void reversalNegatesWithoutTouchingTheOriginal() {
        UUID payer = createAccount("Reversal Payer");
        UUID payee = createAccount("Reversal Payee");

        JsonNode payment = pay(payer, payee, "7.50");
        UUID originalTxn = UUID.fromString(payment.get("transaction").get("id").asText());

        List<Posting> before = postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(originalTxn);
        List<UUID> beforeIds = before.stream().map(Posting::getId).toList();

        ResponseEntity<JsonNode> reversal = reverse(originalTxn);
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID reversalTxn = UUID.fromString(
                reversal.getBody().get("reversalTransaction").get("id").asText());

        // The original transaction still has exactly the postings it had, with
        // the same ids and the same amounts. Nothing was edited.
        List<Posting> after = postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(originalTxn);
        assertThat(after).hasSize(before.size());
        assertThat(after.stream().map(Posting::getId).toList()).isEqualTo(beforeIds);
        assertThat(after.stream().mapToLong(Posting::signedAmountMinor).toArray())
                .isEqualTo(before.stream().mapToLong(Posting::signedAmountMinor).toArray());

        // The reversal balances on its own...
        assertThat(netOfTransaction(reversalTxn)).isZero();

        // ...and each leg is the exact opposite of a leg on the original.
        for (Posting originalLeg : before) {
            Posting mirror = postings.findByTransactionIdOrderByTypeAscCreatedAtAsc(reversalTxn).stream()
                    .filter(p -> p.getAccountId().equals(originalLeg.getAccountId()))
                    .findFirst().orElseThrow();
            assertThat(mirror.getType()).isEqualTo(originalLeg.getType().opposite());
            assertThat(mirror.getAmountMinor()).isEqualTo(originalLeg.getAmountMinor());
            assertThat(mirror.signedAmountMinor()).isEqualTo(-originalLeg.signedAmountMinor());
        }

        // Together, the two transactions leave both accounts where they started.
        assertThat(balanceOf(payer)).isZero();
        assertThat(balanceOf(payee)).isZero();
    }

    @Test
    @DisplayName("a transaction can only be reversed once")
    void secondReversalIsRejected() {
        UUID payer = createAccount("Once Payer");
        UUID payee = createAccount("Once Payee");
        UUID originalTxn = UUID.fromString(
                pay(payer, payee, "3.00").get("transaction").get("id").asText());

        assertThat(reverse(originalTxn).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> second = reverse(originalTxn);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getBody().get("error").asText()).isEqualTo("transaction_already_reversed");

        assertThat(countReversals(originalTxn)).isEqualTo(1);
        assertThat(balanceOf(payer)).isZero();
    }

    @Test
    @DisplayName("reversing an unknown transaction is a 404")
    void reversalOfUnknownTransactionIsNotFound() {
        assertThat(reverse(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------- atomicity ----------

    @Test
    @DisplayName("a failure part-way through a refund leaves nothing behind in the database")
    void failureMidWriteCommitsNothing() {
        UUID payer = createAccount("Atomic Payer");
        UUID payee = createAccount("Atomic Payee");
        UUID paymentId = UUID.fromString(pay(payer, payee, "5.00").get("paymentId").asText());

        long transactionsBefore = countAll("transactions");
        long postingsBefore = countAll("postings");
        long refundsBefore = countAll("refunds");
        long payerBalanceBefore = balanceOf(payer);

        // RefundService writes in this order:
        //   1. TransactionService.createBalanced -> INSERT transaction
        //   2.                                   -> INSERT posting, INSERT posting, flush
        //   3. refunds.save(...)                 -> INSERT refund
        // Making step 3 throw means steps 1 and 2 have already reached the
        // database (createBalanced flushes) when the failure happens. If the
        // transactional boundary were wrong, those rows would survive.
        doThrow(new IllegalStateException("simulated failure after the postings were written"))
                .when(refundRepositorySpy).save(any(Refund.class));

        try {
            assertThatThrownBy(() -> refundService.refund(paymentId, 200L, "doomed"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("simulated failure");

            // Read the database directly, outside any transaction of ours.
            assertThat(countAll("transactions"))
                    .as("the refund transaction row must have been rolled back")
                    .isEqualTo(transactionsBefore);
            assertThat(countAll("postings"))
                    .as("both refund postings must have been rolled back")
                    .isEqualTo(postingsBefore);
            assertThat(countAll("refunds"))
                    .as("no refund row")
                    .isEqualTo(refundsBefore);
            assertThat(balanceOf(payer))
                    .as("a partially applied refund would have moved this")
                    .isEqualTo(payerBalanceBefore);
        } finally {
            reset(refundRepositorySpy);
        }

        // The same refund succeeds once the induced failure is gone, proving the
        // rollback left no broken state behind.
        assertThat(refund(paymentId, "2.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countAll("refunds")).isEqualTo(refundsBefore + 1);
    }

    // ---------- direct database reads ----------

    private long countAll(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long countRefunds(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM refunds WHERE payment_id = ?", Long.class, paymentId);
    }

    private long countReversals(UUID originalTransactionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM reversals WHERE original_transaction_id = ?",
                Long.class, originalTransactionId);
    }
}
