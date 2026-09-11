package com.ledgerguard.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.consumers.LedgerEventConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dual-write gap, attacked from four directions.
 *
 * <p>The transactional outbox exists to close one specific hole: money moves in
 * the database and the announcement of it goes somewhere else, so any failure
 * between the two leaves the two disagreeing. Phase 4 argued the hole is closed.
 * These scenarios try to open it.
 *
 * <p>Each one asserts the same two things in different circumstances — no event
 * is lost, and no event produces two effects — because those are the only two
 * ways the pattern can fail. Everything else is detail.
 */
class OutboxChaosTest extends ChaosScenario {

    @Autowired
    private LedgerEventConsumer consumer;

    /**
     * SCENARIO 1 — the broker is simply gone.
     *
     * <p>Attacks: the assumption that publishing is part of paying. If it were,
     * a broker outage would become a payments outage.
     */
    @Test
    @DisplayName("S1: with the broker unreachable the ledger still commits and the event waits")
    void brokerUnavailableDoesNotStopTheLedger() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");

        broker.unavailable();
        JsonNode payment = pay(payer, payee, 1025L, "USD");

        // The money moved regardless of the broker's state.
        assertThat(invariants.balanceMinorUnits(payer, "USD")).isEqualTo(-1025L);
        assertThat(invariants.balanceMinorUnits(payee, "USD")).isEqualTo(1025L);
        assertThat(count("SELECT COUNT(*) FROM payments")).isEqualTo(1);

        assertThat(publisher().drainOnce())
                .as("nothing can be published while the broker is unreachable")
                .isZero();
        assertThat(broker.deliveredCount()).isZero();
        assertThat(unpublishedOutboxRows())
                .as("the event waits in the outbox; it is not dropped")
                .isEqualTo(1);
        invariants.allHold();

        // The broker comes back. Nothing intervenes but the next poll.
        broker.healthy();
        assertThat(publisher().drainOnce()).isEqualTo(1);
        assertThat(unpublishedOutboxRows()).isZero();
        assertThat(broker.deliveredCount()).isEqualTo(1);

        consumer.resetCounters();
        broker.deliveredPayloads().forEach(consumer::onEvent);
        assertThat(consumer.handledCount()).isEqualTo(1);

        assertThat(paymentIdOf(payment)).isNotNull();
        invariants.allHold();
    }

    /**
     * SCENARIO 2 — the broker accepted the event and said nothing.
     *
     * <p>Attacks: the temptation to treat a send failure as "it did not happen".
     * This is the state that makes delivery at-least-once rather than
     * exactly-once, and the only defence is the consumer.
     */
    @Test
    @DisplayName("S2: an acknowledgement lost after the broker accepted causes a duplicate, not a loss")
    void ackLostAfterAcceptanceProducesADuplicateNotALoss() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        pay(payer, payee, 2500L, "USD");

        broker.acceptEverythingButLoseEveryAck();
        assertThat(publisher().drainOnce())
                .as("the publisher believes it published nothing")
                .isZero();

        assertThat(broker.deliveredCount())
                .as("the broker nevertheless has the event")
                .isEqualTo(1);
        assertThat(unpublishedOutboxRows())
                .as("leaving the row unpublished is correct: a duplicate beats a loss")
                .isEqualTo(1);

        // Next cycle, with acknowledgements working again.
        broker.healthy();
        assertThat(publisher().drainOnce()).isEqualTo(1);
        assertThat(broker.deliveredCount())
                .as("the same event is now on the broker twice — at-least-once delivery, working")
                .isEqualTo(2);

        List<String> payloads = broker.deliveredPayloads();
        assertThat(payloads.get(0))
                .as("both copies must be byte-identical, or consumer dedupe has nothing to key on")
                .isEqualTo(payloads.get(1));

        consumer.resetCounters();
        payloads.forEach(consumer::onEvent);

        assertThat(consumer.handledCount())
                .as("two deliveries, one effect")
                .isEqualTo(1);
        assertThat(consumer.skippedAsDuplicateCount()).isEqualTo(1);
        invariants.allHold();
    }

    /**
     * SCENARIO 3 — the process dies between sending and recording that it sent.
     *
     * <p>Attacks: the exact gap {@code OutboxPublisher} documents. Modelled as
     * the database refusing the UPDATE that marks the batch published, which is
     * indistinguishable from a crash at that instant: the events are on the
     * broker and nothing durable records it.
     *
     * <p>This scenario also settles a question the javadoc raised. Marks are
     * applied by Hibernate at commit, and the whole drain is one transaction, so
     * a failure here rolls back the marks for the <em>entire batch</em> — not
     * just the event that failed. Every event in the batch is therefore
     * republished, which is safe but is not what "events already confirmed keep
     * their published_at" implied. The javadoc was corrected to match.
     */
    @Test
    @DisplayName("S3: a crash between send and mark republishes the whole batch, and loses nothing")
    void crashBetweenSendAndMarkRepublishesRatherThanLoses() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        pay(payer, payee, 100L, "USD");
        pay(payer, payee, 200L, "USD");
        pay(payer, payee, 300L, "USD");

        assertThat(unpublishedOutboxRows()).isEqualTo(3);

        broker.healthy();
        // The UPDATE that records publication is what fails. Everything before
        // it — including all three sends — has already happened.
        database.failAlways(sql -> sql.contains("update outbox_events"),
                ChaosDataSource.connectionDropped());

        assertThatThrownBy(() -> publisher().drainOnce())
                .as("the drain must fail loudly rather than quietly believing it published")
                .isNotNull();

        assertThat(broker.deliveredCount())
                .as("all three reached the broker before the mark was attempted")
                .isEqualTo(3);
        assertThat(unpublishedOutboxRows())
                .as("the whole batch's marks rolled back with the transaction, not just the failed one")
                .isEqualTo(3);
        assertThat(publishedOutboxRows()).isZero();

        // The database recovers; the next poll republishes everything.
        database.healthy();
        assertThat(publisher().drainOnce()).isEqualTo(3);
        assertThat(unpublishedOutboxRows()).isZero();

        assertThat(broker.deliveredCount())
                .as("three events, six deliveries: nothing lost, everything duplicated")
                .isEqualTo(6);

        consumer.resetCounters();
        broker.deliveredPayloads().forEach(consumer::onEvent);

        assertThat(consumer.handledCount())
                .as("six deliveries of three events must produce exactly three effects")
                .isEqualTo(3);
        assertThat(consumer.skippedAsDuplicateCount()).isEqualTo(3);
        invariants.allHold();
    }

    /**
     * SCENARIO 3b — the dual-write gap itself.
     *
     * <p>Scenario 3 kills the process between sending and recording the send.
     * This kills it one step earlier and one layer deeper: between the ledger
     * write and the outbox write, which is the gap the transactional outbox
     * pattern exists to close.
     *
     * <p>In a system that published directly, this is where money moves and the
     * announcement never happens — a loss no retry can recover, because nothing
     * durable records that an event was ever owed. Here the outbox row is
     * written by {@code OutboxRecorder} with {@code Propagation.MANDATORY},
     * inside the caller's transaction, so failing that INSERT must take the
     * whole payment down with it.
     *
     * <p>The assertion is therefore the opposite of the other scenarios: not
     * "the ledger survived" but <b>"the ledger refused to survive alone"</b>.
     */
    @Test
    @DisplayName("S3b: a payment cannot commit without its event; failing the outbox write rolls back the money")
    void theLedgerCannotCommitWithoutItsEvent() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");

        LedgerInvariants.Snapshot before = invariants.snapshot();

        database.failOnce(sql -> sql.contains("insert into outbox_events"),
                ChaosDataSource.connectionDropped());

        assertThatThrownBy(() -> pay(payer, payee, 9900L, "USD"))
                .as("if the event cannot be recorded, the payment must not happen either")
                .isNotNull();
        assertThat(database.firedCount()).isEqualTo(1);
        database.healthy();

        // Not one row of it: no payment, no transaction, no postings, no event.
        invariants.nothingWasWrittenSince(before).allHold();
        assertThat(invariants.balanceMinorUnits(payer, "USD"))
                .as("money must not have moved for an announcement that was never recorded")
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events")).isZero();

        // And the retry is clean, leaving exactly one payment and one event.
        pay(payer, payee, 9900L, "USD");
        assertThat(count("SELECT COUNT(*) FROM payments")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events")).isEqualTo(1);
        assertThat(invariants.balanceMinorUnits(payer, "USD")).isEqualTo(-9900L);
        invariants.allHold();
    }

    /**
     * SCENARIO 4 — one event in a batch is refused.
     *
     * <p>Attacks: batch handling. The failure must not take down the events
     * around it, and — unlike scenario 3 — must not manufacture duplicates,
     * because a refused send never reached the broker at all.
     *
     * <p>Which event fails is chosen by seed rather than hard-coded, so the
     * scenario is not accidentally only testing the first or last position.
     */
    @Test
    @DisplayName("S4: one refused send stops the batch without losing or duplicating anything")
    void aRefusedSendStopsTheBatchCleanly() {
        Chaos chaos = Chaos.of(20260911L);
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");

        int events = 5;
        for (int i = 1; i <= events; i++) {
            pay(payer, payee, i * 100L, "USD");
        }
        assertThat(unpublishedOutboxRows()).isEqualTo(events);

        int failingPosition = chaos.pickBetween(1, events);
        broker.failOnlySend(failingPosition, ChaosKafkaTemplate.Outcome.REJECTED);

        int published = publisher().drainOnce();

        assertThat(published)
                .as("%s: send %d was refused, so the %d before it published and the batch stopped",
                        chaos, failingPosition, failingPosition - 1)
                .isEqualTo(failingPosition - 1);
        assertThat(publishedOutboxRows()).isEqualTo(failingPosition - 1);
        assertThat(unpublishedOutboxRows()).isEqualTo(events - failingPosition + 1);
        assertThat(broker.deliveredCount()).isEqualTo(failingPosition - 1);

        // Recovery publishes exactly the remainder.
        broker.healthy();
        assertThat(publisher().drainOnce()).isEqualTo(events - failingPosition + 1);
        assertThat(unpublishedOutboxRows()).isZero();

        assertThat(broker.deliveredCount())
                .as("a refused send never reached the broker, so recovery must not duplicate anything")
                .isEqualTo(events);
        assertThat(broker.deliveredPayloads()).doesNotHaveDuplicates();

        consumer.resetCounters();
        broker.deliveredPayloads().forEach(consumer::onEvent);
        assertThat(consumer.handledCount()).isEqualTo(events);
        assertThat(consumer.skippedAsDuplicateCount()).isZero();
        invariants.allHold();
    }
}
