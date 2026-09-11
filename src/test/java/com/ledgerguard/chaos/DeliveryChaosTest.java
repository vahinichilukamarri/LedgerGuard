package com.ledgerguard.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.consumers.LedgerEventConsumer;
import com.ledgerguard.settlement.SettlementSimulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Messages arriving twice, and arriving in the wrong order.
 *
 * <p>Kafka guarantees ordering within a partition and delivery at least once. It
 * guarantees nothing about ordering <em>across</em> partitions, and "at least
 * once" is a promise about the floor, not the ceiling. Both consumers here have
 * to be correct under the messy version of that contract rather than the tidy
 * one.
 *
 * <p>Both are exercised together on purpose. They deduplicate by different
 * mechanisms — {@code LedgerEventConsumer} claims a row in
 * {@code processed_events}, {@code SettlementSimulator} derives a unique
 * external id from the event id — and testing one would say nothing about the
 * other.
 */
class DeliveryChaosTest extends ChaosScenario {

    @Autowired
    private LedgerEventConsumer consumer;

    @Autowired
    private SettlementSimulator simulator;

    /**
     * SCENARIO 8 — the same event delivered many times to both consumers.
     *
     * <p>Attacks: the assumption that a redelivery is rare enough to ignore. The
     * simulator is the more interesting target: a second settlement record for
     * one event would surface later as a DUPLICATE_SETTLEMENT incident blaming
     * the ledger for a bug in the consumer.
     */
    @Test
    @DisplayName("S8: an event redelivered eight times produces one effect in each consumer")
    void redeliveryProducesOneEffectInBothConsumers() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        JsonNode payment = pay(payer, payee, 4200L, "USD");
        UUID transactionId = transactionIdOf(payment);

        publisher().drainOnce();
        String event = broker.deliveredPayloads().get(0);

        consumer.resetCounters();
        for (int delivery = 1; delivery <= 8; delivery++) {
            consumer.onEvent(event);
            simulator.onLedgerEvent(event);

            assertThat(count("SELECT COUNT(*) FROM processed_events"))
                    .as("delivery %d must not add a second claim", delivery)
                    .isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM settlement_records"))
                    .as("delivery %d must not add a second settlement", delivery)
                    .isEqualTo(1);
        }

        assertThat(consumer.handledCount()).isEqualTo(1);
        assertThat(consumer.skippedAsDuplicateCount()).isEqualTo(7);

        assertThat(count("SELECT COUNT(*) FROM settlement_records WHERE external_reference = ?",
                transactionId.toString())).isEqualTo(1);
        assertThat(count("SELECT amount_minor FROM settlement_records LIMIT 1")).isEqualTo(4200L);
        invariants.allHold();
    }

    /**
     * SCENARIO 9 — events arrive in the reverse of the order they happened.
     *
     * <p>Attacks: any hidden assumption that a refund is processed after the
     * payment it refunds. Nothing in the settlement path should care, because
     * each event names the transaction it settles; this proves that rather than
     * trusting it.
     */
    @Test
    @DisplayName("S9: a refund settling before its own payment still settles both correctly")
    void outOfOrderArrivalSettlesBothCorrectly() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        JsonNode payment = pay(payer, payee, 1000L, "USD");
        UUID paymentTransaction = transactionIdOf(payment);

        JsonNode refund = refund(paymentIdOf(payment), 250L, "USD");
        UUID refundTransaction = transactionIdOf(refund);

        publisher().drainOnce();
        List<String> inOrder = broker.deliveredPayloads();
        assertThat(inOrder).hasSize(2);

        // Backwards: the refund is settled before the payment exists downstream.
        List<String> reversed = new ArrayList<>(inOrder);
        java.util.Collections.reverse(reversed);

        consumer.resetCounters();
        reversed.forEach(event -> {
            consumer.onEvent(event);
            simulator.onLedgerEvent(event);
        });

        assertThat(consumer.handledCount()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM settlement_records")).isEqualTo(2);

        assertThat(count("SELECT amount_minor FROM settlement_records WHERE external_reference = ?",
                paymentTransaction.toString()))
                .as("the payment settled for its own amount despite arriving second")
                .isEqualTo(1000L);
        assertThat(count("SELECT amount_minor FROM settlement_records WHERE external_reference = ?",
                refundTransaction.toString()))
                .as("the refund settled for its own amount despite arriving first")
                .isEqualTo(250L);
        invariants.allHold();
    }

    /**
     * SCENARIO 10 — many aggregates, duplicated and shuffled together.
     *
     * <p>Attacks: deduplication that is really just "ignore what I saw last".
     * Duplicates here are separated by other events, so a consumer that only
     * remembers the previous message would double-count and a consumer that
     * keyed on the wrong field would suppress a genuine one.
     *
     * <p>The duplication counts and the arrival order both come from a seed, so
     * this is one fixed, reproducible interleaving rather than a different one
     * each run.
     */
    @Test
    @DisplayName("S10: duplicated and shuffled deliveries across six aggregates still produce one effect each")
    void interleavedDuplicatesNeverDoubleCountOrSuppress() {
        Chaos chaos = Chaos.of(70701L);
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");

        int aggregates = 6;
        for (int i = 1; i <= aggregates; i++) {
            pay(payer, payee, i * 111L, "USD");
        }

        publisher().drainOnce();
        List<String> distinct = broker.deliveredPayloads();
        assertThat(distinct).hasSize(aggregates);

        // Between one and three copies of each, then shuffled together.
        List<String> deliveries = new ArrayList<>();
        for (String event : distinct) {
            int copies = chaos.pickBetween(1, 3);
            for (int copy = 0; copy < copies; copy++) {
                deliveries.add(event);
            }
        }
        List<String> arrival = chaos.shuffle(deliveries);

        consumer.resetCounters();
        arrival.forEach(event -> {
            consumer.onEvent(event);
            simulator.onLedgerEvent(event);
        });

        assertThat(consumer.handledCount())
                .as("%s: %d deliveries of %d events must produce %d effects",
                        chaos, arrival.size(), aggregates, aggregates)
                .isEqualTo(aggregates);
        assertThat(consumer.skippedAsDuplicateCount()).isEqualTo(arrival.size() - aggregates);

        assertThat(count("SELECT COUNT(*) FROM processed_events")).isEqualTo(aggregates);
        assertThat(count("SELECT COUNT(*) FROM settlement_records")).isEqualTo(aggregates);

        // Every payment settled for its own amount: nothing was suppressed by a
        // neighbour's duplicate, and no amount was attributed to the wrong one.
        for (int i = 1; i <= aggregates; i++) {
            assertThat(count("SELECT COUNT(*) FROM settlement_records WHERE amount_minor = ?", i * 111L))
                    .as("the payment of %d minor units settled exactly once", i * 111L)
                    .isEqualTo(1);
        }
        invariants.allHold();
    }
}
