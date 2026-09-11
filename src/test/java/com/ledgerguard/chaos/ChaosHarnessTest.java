package com.ledgerguard.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The harness testing itself.
 *
 * <h2>Why this is not paranoia</h2>
 *
 * Every other test in this package proves something by surviving an injected
 * fault. All of them would pass, loudly and falsely, if the fault were never
 * actually injected — a {@code BeanPostProcessor} that ran too late to wrap the
 * DataSource, or a {@code @Primary} that lost to the autoconfigured
 * {@code KafkaTemplate}, produces a suite that is green because nothing ever
 * broke.
 *
 * <p>A chaos suite that cannot fail is worth less than no chaos suite, because
 * it is believed. So before any scenario asserts that the ledger survived a
 * fault, this asserts that the fault is real: armed faults throw, disarmed ones
 * do not, the broker distinguishes its three outcomes, and the application reads
 * the clock the scenario controls.
 */
class ChaosHarnessTest extends ChaosScenario {

    @Test
    @DisplayName("the DataSource the application uses really is the wrapped one")
    void dataSourceIsWrappedAndReachable() {
        // Not merely "a ChaosDataSource bean exists" — the queries the
        // application issues have to pass through it, or arming does nothing.
        database.failOnce(sql -> sql.contains("from accounts"), ChaosDataSource.connectionDropped());

        assertThatThrownBy(() -> count("SELECT COUNT(*) FROM accounts"))
                .as("an armed fault must reach a query the application makes")
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause()
                .hasMessageContaining("chaos: connection to server was lost");

        assertThat(database.firedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a fault fires once, matches only what it was armed for, and can be disarmed")
    void faultsAreTargetedAndOneShot() {
        database.failOnce(sql -> sql.contains("from payments"), ChaosDataSource.connectionDropped());

        // A statement the predicate does not match is untouched.
        assertThatCode(() -> count("SELECT COUNT(*) FROM accounts")).doesNotThrowAnyException();

        assertThatThrownBy(() -> count("SELECT COUNT(*) FROM payments"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);

        // failOnce means once: the second attempt goes through.
        assertThatCode(() -> count("SELECT COUNT(*) FROM payments")).doesNotThrowAnyException();

        database.failAlways(sql -> sql.contains("from refunds"), ChaosDataSource.statementTimeout());
        assertThatThrownBy(() -> count("SELECT COUNT(*) FROM refunds")).isNotNull();
        assertThatThrownBy(() -> count("SELECT COUNT(*) FROM refunds")).isNotNull();

        database.healthy();
        assertThatCode(() -> count("SELECT COUNT(*) FROM refunds")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the broker distinguishes delivered, ack-lost and rejected")
    void brokerOutcomesAreDistinct() {
        broker.healthy();
        assertThat(broker.send("t", "k", "delivered").isCompletedExceptionally()).isFalse();
        assertThat(broker.deliveredCount()).isEqualTo(1);

        // The distinction the whole class exists for: the broker HAS it, and the
        // producer is told it does not.
        broker.acceptEverythingButLoseEveryAck();
        assertThat(broker.send("t", "k", "ack-lost").isCompletedExceptionally()).isTrue();
        assertThat(broker.deliveredCount())
                .as("an ack-lost record is on the broker even though the send reported failure")
                .isEqualTo(2);

        broker.unavailable();
        assertThat(broker.send("t", "k", "rejected").isCompletedExceptionally()).isTrue();
        assertThat(broker.deliveredCount())
                .as("a rejected record never reached the broker")
                .isEqualTo(2);

        assertThat(broker.deliveredPayloads()).containsExactly("delivered", "ack-lost");
        assertThat(broker.attemptedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("the outbox publisher is wired to the fake broker, not a real one")
    void publisherUsesTheFakeBroker() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        pay(payer, payee, 1025L, "USD");

        assertThat(unpublishedOutboxRows()).isEqualTo(1);

        broker.healthy();
        assertThat(publisher().drainOnce()).isEqualTo(1);

        assertThat(broker.deliveredCount())
                .as("the publisher must be sending through the harness, not to a real broker")
                .isEqualTo(1);
        assertThat(unpublishedOutboxRows()).isZero();
    }

    @Test
    @DisplayName("the application writes timestamps from the clock the scenario controls")
    void applicationReadsTheTickingClock() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        pay(payer, payee, 500L, "USD");

        java.time.Instant createdAt = jdbc.queryForObject(
                "SELECT created_at FROM transactions LIMIT 1", java.sql.Timestamp.class).toInstant();
        assertThat(createdAt)
                .as("a transaction must be stamped with the scenario's clock, not the wall clock")
                .isEqualTo(ChaosConfig.EPOCH);

        clock.advance(Duration.ofHours(3));
        pay(payer, payee, 500L, "USD");

        java.time.Instant latest = jdbc.queryForObject(
                "SELECT MAX(created_at) FROM transactions", java.sql.Timestamp.class).toInstant();
        assertThat(latest).isEqualTo(ChaosConfig.EPOCH.plus(Duration.ofHours(3)));
    }

    @Test
    @DisplayName("each scenario starts from an empty ledger")
    void startingStateIsKnown() {
        // Whatever the previous test wrote, this one begins with nothing.
        assertThat(invariants.snapshot())
                .isEqualTo(new LedgerInvariants.Snapshot(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("seeded chaos makes the same choices every run")
    void chaosIsReproducible() {
        assertThat(Chaos.of(42).pick(1000)).isEqualTo(Chaos.of(42).pick(1000));
        assertThat(Chaos.of(42).shuffle(java.util.List.of(1, 2, 3, 4, 5)))
                .isEqualTo(Chaos.of(42).shuffle(java.util.List.of(1, 2, 3, 4, 5)));
        assertThat(Chaos.of(7).seed()).isEqualTo(7L);
    }
}
