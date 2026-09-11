package com.ledgerguard.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.accounts.AccountService;
import com.ledgerguard.config.Money;
import com.ledgerguard.outbox.OutboxEventRepository;
import com.ledgerguard.outbox.OutboxPublisher;
import com.ledgerguard.payments.PaymentController;
import com.ledgerguard.payments.dto.CreatePaymentRequest;
import com.ledgerguard.refunds.RefundController;
import com.ledgerguard.refunds.dto.CreateRefundRequest;
import com.ledgerguard.reversals.ReversalController;
import com.ledgerguard.reversals.dto.CreateReversalRequest;
import com.ledgerguard.support.LedgerPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The shared rig every chaos scenario runs on.
 *
 * <h2>A known starting state, not an accumulated one</h2>
 *
 * Each scenario truncates the ledger before it begins. That is not tidiness: the
 * brief for this phase asks each scenario to set up a <em>known</em> state, and
 * a scenario that begins with whatever the previous one happened to leave behind
 * does not have one. It also makes the assertions exact — "two postings exist"
 * rather than "two more postings than before" — and exact assertions are the
 * ones that catch a fault writing something unexpected.
 *
 * <p>One container and one Spring context serve every scenario class, because
 * the truncation provides the isolation that a container per class would
 * otherwise have to.
 *
 * <h2>Nothing runs on a timer</h2>
 *
 * The outbox publisher is disabled as a bean and constructed by hand in
 * {@link #publisher()}, and the Kafka listener containers never start. So no
 * scheduled thread and no consumer poll can fire in the middle of a scenario and
 * change the state it is asserting on. Every event that moves, moves because a
 * scenario moved it.
 *
 * <p>That is the difference between a chaos test and a flaky one: the faults are
 * the only source of nondeterminism, and {@link Chaos} makes even those
 * repeatable.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // No scheduled publisher. Scenarios drive drainOnce() themselves,
                // so a poll cannot land between an injection and its assertion.
                "ledgerguard.outbox.publisher.enabled=false",
                // No consumer threads either; the consumers are invoked directly.
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.admin.auto-create=false",
                "ledgerguard.reconciliation.grace-seconds=5",
                "logging.level.com.ledgerguard=WARN",
                "logging.level.org.springframework=WARN",
                "logging.level.org.hibernate=WARN"
        })
@Import(ChaosConfig.class)
abstract class ChaosScenario {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = startShared();

    private static PostgreSQLContainer<?> startShared() {
        PostgreSQLContainer<?> container = LedgerPostgres.newContainer();
        container.start();
        return container;
    }

    /** Every table the ledger owns, in one statement so foreign keys cannot argue. */
    private static final String TRUNCATE_ALL = """
            TRUNCATE TABLE reconciliation_incidents, reconciliation_runs, settlement_records,
                           processed_events, outbox_events, idempotency_keys,
                           reversals, refunds, postings, payments, transactions, accounts
            RESTART IDENTITY CASCADE
            """;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected LedgerInvariants invariants;

    @Autowired
    protected ChaosKafkaTemplate broker;

    /**
     * The wrapped DataSource, resolved by cast rather than injected as its own
     * bean type — a {@code @Bean ChaosDataSource(DataSource)} would be asked to
     * inject itself, since the post-processed DataSource is the very object.
     */
    protected ChaosDataSource database;

    @Autowired
    private DataSource dataSource;

    @Autowired
    protected TickingClock clock;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    private AccountService accounts;

    @Autowired
    private PaymentController payments;

    @Autowired
    private RefundController refunds;

    @Autowired
    private ReversalController reversals;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void knownStartingState() {
        database = wrappedDataSource();

        // Disarm first: a fault left armed by a previous scenario would fire
        // during the truncate and fail the next scenario for the wrong reason.
        database.healthy();
        broker.reset();
        clock.resetTo(ChaosConfig.EPOCH);
        jdbc.execute(TRUNCATE_ALL);
    }

    /**
     * Fails loudly rather than letting the suite pass vacuously. If the
     * post-processor ever stops running, every scenario would still be green
     * while injecting nothing at all.
     */
    private ChaosDataSource wrappedDataSource() {
        if (dataSource instanceof ChaosDataSource chaos) {
            return chaos;
        }
        throw new IllegalStateException("the DataSource was not wrapped by ChaosConfig; "
                + "faults would be silently inert and every scenario would pass for no reason");
    }

    /**
     * The real publisher, built by hand rather than injected.
     *
     * <p>It has to be constructed here because the scheduled bean is switched
     * off: leaving it on would mean a background poll could drain the outbox
     * between a scenario's injection and its assertion. This is the same class
     * with the same collaborators, minus the timer.
     *
     * <p>A one-second send timeout keeps a rejected send from stalling the suite;
     * the fake broker fails immediately anyway, so the timeout never elapses.
     */
    protected OutboxPublisher publisher() {
        return new OutboxPublisher(outbox, broker, transactionManager, clock, 100, 1);
    }

    // ------------------------------------------------------- ledger actions

    protected UUID createAccount(String currency) {
        return accounts.create("chaos-" + UUID.randomUUID(), currency).getId();
    }

    protected JsonNode pay(UUID source, UUID destination, long amountMinor, String currency) {
        return parse(payments.create(
                "chaos-" + UUID.randomUUID(),
                new CreatePaymentRequest(source, destination,
                        Money.toMajorUnits(amountMinor, currency), currency, null)));
    }

    protected JsonNode refund(UUID paymentId, long amountMinor, String currency) {
        return parse(refunds.refund(paymentId, "chaos-" + UUID.randomUUID(),
                new CreateRefundRequest(Money.toMajorUnits(amountMinor, currency), null)));
    }

    protected JsonNode reverse(UUID transactionId) {
        return parse(reversals.reverse(transactionId, "chaos-" + UUID.randomUUID(),
                new CreateReversalRequest(null)));
    }

    // --------------------------------------------------------- reading back

    protected static UUID paymentIdOf(JsonNode paymentResponse) {
        return UUID.fromString(paymentResponse.get("paymentId").asText());
    }

    protected static UUID transactionIdOf(JsonNode paymentOrRefundResponse) {
        return UUID.fromString(paymentOrRefundResponse.get("transaction").get("id").asText());
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    protected long unpublishedOutboxRows() {
        return count("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL");
    }

    protected long publishedOutboxRows() {
        return count("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NOT NULL");
    }

    protected BigDecimal majorUnits(long minorUnits, String currency) {
        return Money.toMajorUnits(minorUnits, currency);
    }

    private JsonNode parse(ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("controller returned unparseable JSON", e);
        }
    }
}
