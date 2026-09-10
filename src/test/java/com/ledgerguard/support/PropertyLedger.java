package com.ledgerguard.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.LedgerGuardApplication;
import com.ledgerguard.accounts.AccountService;
import com.ledgerguard.payments.PaymentController;
import com.ledgerguard.payments.dto.CreatePaymentRequest;
import com.ledgerguard.refunds.RefundController;
import com.ledgerguard.refunds.dto.CreateRefundRequest;
import com.ledgerguard.reversals.ReversalController;
import com.ledgerguard.reversals.dto.CreateReversalRequest;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The application, booted once, for property tests to drive.
 *
 * <h2>Why this exists rather than {@code @SpringBootTest}</h2>
 *
 * Spring's {@code SpringExtension} is a JUnit Jupiter extension. jqwik is a
 * separate JUnit Platform engine, so a {@code @Property} method never passes
 * through Jupiter's lifecycle and never gets a Spring context injected. Rather
 * than pull in a bridge library, the context is built here directly and held for
 * the life of the JVM: one boot and one container shared by every property
 * class, instead of one of each per class.
 *
 * <h2>Why it calls controllers instead of HTTP</h2>
 *
 * The controllers are ordinary beans. Calling them directly runs the whole
 * production path that matters here — {@code Money} conversion at the boundary,
 * {@code IdempotencyService}, the services, the balance check, the real
 * migrated schema — without a servlet container or a socket per try. At jqwik's
 * tries counts that difference is the difference between a suite that runs and
 * one that does not.
 *
 * <p>The one thing it gives up is {@code ApiExceptionHandler}, which maps domain
 * exceptions onto status codes. Properties here therefore assert on the
 * exception thrown rather than on an HTTP status. That is the more precise
 * assertion anyway, and the status mapping is already covered by the Phase 1-5
 * integration tests.
 */
public final class PropertyLedger {

    private PropertyLedger() {
    }

    private static final class Context {
        private static final ConfigurableApplicationContext INSTANCE = boot();

        private static ConfigurableApplicationContext boot() {
            PostgreSQLContainer<?> postgres = LedgerPostgres.shared();
            // Passed as command-line arguments, not as builder properties.
            // Builder properties become the application's *default* property
            // source, which application.yml outranks — so the container URL
            // would be silently ignored and the context would try localhost.
            ConfigurableApplicationContext context = new SpringApplicationBuilder(
                    LedgerGuardApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(),
                            // No broker in the property suite. Outbox rows are still
                            // written inside the ledger transaction, which is what the
                            // properties care about; nothing drains them.
                            "--ledgerguard.outbox.publisher.enabled=false",
                            "--spring.kafka.listener.auto-startup=false",
                            "--spring.kafka.admin.auto-create=false",
                            "--logging.level.com.ledgerguard=WARN",
                            "--logging.level.org.springframework=WARN",
                            "--logging.level.org.hibernate=WARN");

            // Nothing else closes this context: it deliberately outlives every
            // property class. Without an explicit close its non-daemon threads
            // keep the forked JVM alive past surefire's exit timeout, which
            // makes a passing build print a shutdown error.
            Runtime.getRuntime().addShutdownHook(new Thread(context::close, "property-ledger-close"));
            return context;
        }
    }

    public static <T> T bean(Class<T> type) {
        return Context.INSTANCE.getBean(type);
    }

    // ---------------------------------------------------------------- writes

    public static UUID createAccount(String currency) {
        return bean(AccountService.class)
                .create("prop-" + UUID.randomUUID(), currency)
                .getId();
    }

    /** A payment through the real controller, with a fresh idempotency key. */
    public static JsonNode pay(UUID source, UUID destination, BigDecimal amount, String currency) {
        return pay(freshKey(), source, destination, amount, currency);
    }

    /** A payment under a caller-chosen key, so replays are expressible. */
    public static JsonNode pay(String idempotencyKey, UUID source, UUID destination,
                               BigDecimal amount, String currency) {
        ResponseEntity<String> response = bean(PaymentController.class).create(
                idempotencyKey,
                new CreatePaymentRequest(source, destination, amount, currency, null));
        return parse(response);
    }

    public static JsonNode refund(UUID paymentId, BigDecimal amount) {
        return refund(freshKey(), paymentId, amount);
    }

    public static JsonNode refund(String idempotencyKey, UUID paymentId, BigDecimal amount) {
        ResponseEntity<String> response = bean(RefundController.class).refund(
                paymentId, idempotencyKey, new CreateRefundRequest(amount, null));
        return parse(response);
    }

    public static JsonNode reverse(UUID transactionId) {
        return reverse(freshKey(), transactionId);
    }

    public static JsonNode reverse(String idempotencyKey, UUID transactionId) {
        ResponseEntity<String> response = bean(ReversalController.class).reverse(
                transactionId, idempotencyKey, new CreateReversalRequest(null));
        return parse(response);
    }

    public static String freshKey() {
        return "prop-" + UUID.randomUUID();
    }

    // ------------------------------------------------ response shape readers

    /*
     * The response DTOs name their fields for what they are — paymentId, not id;
     * reversalTransaction, not transaction. Reading them in one place keeps that
     * shape from being restated in seven test classes, each free to get it
     * subtly wrong.
     */

    public static UUID paymentIdOf(JsonNode paymentResponse) {
        return UUID.fromString(paymentResponse.get("paymentId").asText());
    }

    /** The transaction a payment or a refund produced. */
    public static UUID transactionIdOf(JsonNode paymentOrRefundResponse) {
        return UUID.fromString(paymentOrRefundResponse.get("transaction").get("id").asText());
    }

    /** The new transaction a reversal produced, carrying the negating postings. */
    public static UUID reversalTransactionIdOf(JsonNode reversalResponse) {
        return UUID.fromString(reversalResponse.get("reversalTransaction").get("id").asText());
    }

    // ----------------------------------------------------------------- reads

    private static JdbcTemplate jdbc() {
        return bean(JdbcTemplate.class);
    }

    /** Sum of DEBIT minus sum of CREDIT over every posting ever written. Always zero. */
    public static long ledgerNetMinorUnits() {
        return queryLong("SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor "
                + "ELSE -amount_minor END), 0) FROM postings");
    }

    /** Derived balance for one account in one currency, straight from SQL. */
    public static long balanceMinorUnits(UUID accountId, String currency) {
        return queryLong("SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor "
                        + "ELSE -amount_minor END), 0) FROM postings "
                        + "WHERE account_id = ? AND currency = ?",
                accountId, currency);
    }

    /** Every posting on this account, whatever its currency. */
    public static long balanceMinorUnitsAllCurrencies(UUID accountId) {
        return queryLong("SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor "
                + "ELSE -amount_minor END), 0) FROM postings WHERE account_id = ?", accountId);
    }

    public static long postingCount() {
        return queryLong("SELECT COUNT(*) FROM postings");
    }

    public static long postingCountFor(UUID transactionId) {
        return queryLong("SELECT COUNT(*) FROM postings WHERE transaction_id = ?", transactionId);
    }

    public static long transactionNetMinorUnits(UUID transactionId) {
        return queryLong("SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor "
                + "ELSE -amount_minor END), 0) FROM postings WHERE transaction_id = ?", transactionId);
    }

    public static long paymentCount() {
        return queryLong("SELECT COUNT(*) FROM payments");
    }

    public static long transactionCount() {
        return queryLong("SELECT COUNT(*) FROM transactions");
    }

    public static long refundCountFor(UUID paymentId) {
        return queryLong("SELECT COUNT(*) FROM refunds WHERE payment_id = ?", paymentId);
    }

    public static long refundedTotalFor(UUID paymentId) {
        return queryLong("SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE payment_id = ?",
                paymentId);
    }

    public static long reversalCountFor(UUID transactionId) {
        return queryLong("SELECT COUNT(*) FROM reversals WHERE original_transaction_id = ?",
                transactionId);
    }

    public static long idempotencyKeyCount(String key) {
        return queryLong("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", key);
    }

    public static long processedEventCount(UUID eventId) {
        return queryLong("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", eventId);
    }

    /**
     * Net per (transaction, currency) pair across the whole ledger, non-zero
     * entries only. An empty map is the invariant holding.
     *
     * <p>Grouping by currency as well as transaction is the point: a transaction
     * whose currencies cancel each other out would net to zero if you grouped by
     * transaction alone, and must still be reported here.
     */
    public static Map<String, Long> unbalancedTransactionCurrencyPairs() {
        Map<String, Long> result = new LinkedHashMap<>();
        RowCallbackHandler collect = row -> result.put(
                row.getString("transaction_id") + "/" + row.getString("currency"), row.getLong("net"));

        jdbc().query("SELECT transaction_id, currency, "
                + "SUM(CASE WHEN type = 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS net "
                + "FROM postings GROUP BY transaction_id, currency "
                + "HAVING SUM(CASE WHEN type = 'DEBIT' THEN amount_minor "
                + "ELSE -amount_minor END) <> 0", collect);
        return result;
    }

    private static long queryLong(String sql, Object... args) {
        Long value = jdbc().queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static JsonNode parse(ResponseEntity<String> response) {
        try {
            return bean(ObjectMapper.class).readTree(response.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("controller returned unparseable JSON", e);
        }
    }
}
