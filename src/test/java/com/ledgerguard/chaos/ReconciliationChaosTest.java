package com.ledgerguard.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerguard.reconciliation.DiscrepancyType;
import com.ledgerguard.reconciliation.ReconciliationIncident;
import com.ledgerguard.reconciliation.ReconciliationService;
import com.ledgerguard.settlement.SettlementSimulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The external world sending back nonsense, or nothing at all.
 *
 * <p>Reconciliation is the last line of defence, which makes its own failure
 * modes the most dangerous ones in the system. A reconciliation pass that throws
 * on a malformed external record stops checking everything after it; a pass that
 * silently skips one has reported a clean ledger it never verified. Either way
 * the answer is the same shape: a discrepancy nobody hears about.
 *
 * <p>So the invariant these scenarios assert is not "the right incident type was
 * chosen" — {@code ReconcilerTest} owns that. It is <b>nothing is silently
 * dropped</b>: every external record that cannot be matched is accounted for in
 * some incident, and the run completes.
 */
class ReconciliationChaosTest extends ChaosScenario {

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private SettlementSimulator simulator;

    /**
     * SCENARIO 11 — the processor sends records that cannot be matched.
     *
     * <p>Attacks: the matching key. Its own schema stops the external system
     * emitting a malformed <em>row</em>, so what it can actually get wrong is
     * the reference, the amount and the currency — and all three are injected
     * here, including a reference that is not a UUID at all.
     */
    @Test
    @DisplayName("S11: malformed external records are all accounted for, and none is silently dropped")
    void malformedExternalRecordsAreNeverSilentlyDropped() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        JsonNode payment = pay(payer, payee, 1000L, "USD");
        UUID goodTransaction = transactionIdOf(payment);

        // One record that is perfectly fine, so the scenario also proves the
        // malformed ones did not poison the matching of a healthy record.
        publisher().drainOnce();
        simulator.onLedgerEvent(broker.deliveredPayloads().get(0));

        // Four ways the processor can be wrong, all of them schema-legal.
        UUID noReference = injectExternal(null, 500L, "USD", "SETTLED");
        UUID blankReference = injectExternal("   ", 600L, "USD", "SETTLED");
        UUID notAUuid = injectExternal("not-a-transaction-id", 700L, "USD", "SETTLED");
        UUID unknownTransaction = injectExternal(UUID.randomUUID().toString(), 800L, "USD", "SETTLED");

        clock.advanceSeconds(60);

        ReconciliationService.RunResult result = assertRunCompletes();

        Set<UUID> reported = result.incidents().stream()
                .map(ReconciliationIncident::getSettlementRecordId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        assertThat(reported)
                .as("every unmatchable external record must appear in an incident, "
                        + "including the one whose reference is not even a UUID")
                .contains(noReference, blankReference, notAUuid, unknownTransaction);

        assertThat(result.incidents())
                .filteredOn(incident -> goodTransaction.equals(incident.getTransactionId()))
                .as("the correctly settled transaction must still reconcile cleanly")
                .isEmpty();

        invariants.allHold();
    }

    /**
     * SCENARIO 12 — the external feed stops half way through.
     *
     * <p>Attacks: the difference between "not settled yet" and "never settled".
     * Reporting the first is noise that trains people to ignore the alert;
     * missing the second is money that left the ledger and never arrived.
     */
    @Test
    @DisplayName("S12: a truncated external feed reports exactly the transactions it failed to settle")
    void aTruncatedFeedReportsExactlyWhatIsMissing() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");

        JsonNode first = pay(payer, payee, 100L, "USD");
        JsonNode second = pay(payer, payee, 200L, "USD");
        JsonNode third = pay(payer, payee, 300L, "USD");

        publisher().drainOnce();
        List<String> events = broker.deliveredPayloads();
        assertThat(events).hasSize(3);

        // The feed delivers two and then stops.
        simulator.onLedgerEvent(events.get(0));
        simulator.onLedgerEvent(events.get(1));
        assertThat(count("SELECT COUNT(*) FROM settlement_records")).isEqualTo(2);

        // Inside the grace window, the gap is not yet a finding.
        ReconciliationService.RunResult early = assertRunCompletes();
        assertThat(early.incidents())
                .as("a transaction the simulator has not reached yet is not a discrepancy")
                .isEmpty();
        assertThat(early.awaitingSettlement())
                .as("only the unsettled one is awaiting; the two that settled already matched")
                .isEqualTo(1);

        // Past it, the one that never arrived is exactly one finding.
        clock.advanceSeconds(60);
        ReconciliationService.RunResult late = assertRunCompletes();

        assertThat(late.incidents()).hasSize(1);
        ReconciliationIncident incident = late.incidents().get(0);
        assertThat(incident.getDiscrepancyType()).isEqualTo(DiscrepancyType.MISSING_SETTLEMENT);
        assertThat(incident.getTransactionId())
                .as("the finding must name the transaction the feed actually dropped")
                .isEqualTo(transactionIdOf(third));

        assertThat(paymentIdOf(first)).isNotNull();
        assertThat(paymentIdOf(second)).isNotNull();
        invariants.allHold();
    }

    /**
     * SCENARIO 13 — an event reaches the processor with its amount missing.
     *
     * <p>Attacks: the processor's own parsing, and then reconciliation's ability
     * to notice what the processor made of it. An incomplete payload is a
     * partial failure of the integration rather than of either system, and the
     * requirement is that it ends up visible rather than absorbed.
     */
    @Test
    @DisplayName("S13: an incomplete event payload becomes a reported discrepancy, not a silent one")
    void anIncompletePayloadSurfacesAsADiscrepancy() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        JsonNode payment = pay(payer, payee, 5000L, "USD");
        UUID transactionId = transactionIdOf(payment);

        publisher().drainOnce();
        String complete = broker.deliveredPayloads().get(0);

        // Drop the amount field and re-serialize, so the message stays valid
        // JSON. Editing the string instead would leave a dangling comma whenever
        // amountMinor happened to serialize last — and the payload is built from
        // a Map.of, whose iteration order varies between JVM runs. The processor
        // would then skip it as unparseable on some runs and not others, which
        // is both the wrong scenario and a flaky test. The case worth exercising
        // is a payload that parses perfectly and is missing something needed.
        String incomplete = withoutPayloadField(complete, "amountMinor");

        assertThatCode(() -> simulator.onLedgerEvent(incomplete))
                .as("the processor must not blow up on a payload it can still identify")
                .doesNotThrowAnyException();

        assertThat(count("SELECT COUNT(*) FROM settlement_records")).isEqualTo(1);

        clock.advanceSeconds(60);
        ReconciliationService.RunResult result = assertRunCompletes();

        assertThat(result.incidents())
                .as("a settlement that disagrees with the ledger about the money must be reported")
                .hasSize(1);
        ReconciliationIncident incident = result.incidents().get(0);
        assertThat(incident.getDiscrepancyType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(incident.getTransactionId()).isEqualTo(transactionId);
        assertThat(incident.getInternalAmountMinor())
                .as("the ledger's own figure is untouched by the malformed message")
                .isEqualTo(5000L);
        assertThat(incident.getDifferenceMinor()).isEqualTo(5000L);

        invariants.allHold();
    }

    // ------------------------------------------------------------- helpers

    /**
     * Reconciliation must survive whatever it is handed. Wrapping every run in
     * this is the point of the class: a pass that throws has stopped checking.
     */
    private ReconciliationService.RunResult assertRunCompletes() {
        ReconciliationService.RunResult[] captured = new ReconciliationService.RunResult[1];
        assertThatCode(() -> captured[0] = reconciliation.run())
                .as("a reconciliation pass must complete whatever the external world sent")
                .doesNotThrowAnyException();
        return captured[0];
    }

    /** The same envelope with one payload field removed, still valid JSON. */
    private String withoutPayloadField(String envelopeJson, String field) {
        try {
            ObjectNode envelope = (ObjectNode) json.readTree(envelopeJson);
            ((ObjectNode) envelope.get("payload")).remove(field);
            return json.writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not rewrite the envelope", e);
        }
    }

    /** Write a row as the external processor, bypassing the simulator entirely. */
    private UUID injectExternal(String reference, long amountMinor, String currency, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO settlement_records
                            (id, external_id, external_reference, amount_minor, currency, status, settled_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id, "EXT-" + id, reference, amountMinor, currency, status,
                Timestamp.from(clock.instant()));
        return id;
    }
}
