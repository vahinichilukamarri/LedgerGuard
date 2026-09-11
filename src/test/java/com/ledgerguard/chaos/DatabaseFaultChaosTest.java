package com.ledgerguard.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.reconciliation.ReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The connection dies half way through writing money.
 *
 * <p>Phase 2 proved atomicity by making a repository throw. That shows the
 * service handles an exception; it cannot show the database rolls anything back,
 * because no database was involved in the failure. These scenarios kill the
 * connection at the JDBC statement instead, so the rollback under test is
 * PostgreSQL's own.
 *
 * <p>The assertion after each fault is the same and is deliberately total:
 * {@link LedgerInvariants#nothingWasWrittenSince} compares every table the
 * ledger owns. "The payment is absent" would pass while a stray transaction row,
 * an orphaned posting or an outbox event announcing a payment that no longer
 * exists sat in the database unnoticed.
 */
class DatabaseFaultChaosTest extends ChaosScenario {

    @Autowired
    private ReconciliationService reconciliation;

    /**
     * SCENARIO 5 — the connection drops while the postings are being written.
     *
     * <p>Attacks: the moment between the transaction row and the postings that
     * balance it. A partial write here is the one thing the Phase 1 invariant
     * cannot catch on its own, because a transaction with no postings has
     * nothing to be unbalanced about.
     */
    @Test
    @DisplayName("S5: a connection drop mid-payment leaves no trace of the payment at all")
    void connectionDropMidPaymentWritesNothing() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");

        LedgerInvariants.Snapshot before = invariants.snapshot();

        database.failOnce(sql -> sql.contains("insert into postings"),
                ChaosDataSource.connectionDropped());

        assertThatThrownBy(() -> pay(payer, payee, 1025L, "USD"))
                .as("the payment must fail rather than half-succeed")
                .isNotNull();

        assertThat(database.firedCount())
                .as("the fault must actually have fired, or this scenario proves nothing")
                .isEqualTo(1);

        database.healthy();

        invariants.nothingWasWrittenSince(before).allHold();
        assertThat(invariants.balanceMinorUnits(payer, "USD")).isZero();
        assertThat(invariants.balanceMinorUnits(payee, "USD")).isZero();

        // And the ledger still works afterwards: the rollback left nothing that
        // blocks the retry, which is the other half of recovering cleanly.
        JsonNode retried = pay(payer, payee, 1025L, "USD");
        assertThat(paymentIdOf(retried)).isNotNull();
        assertThat(invariants.balanceMinorUnits(payer, "USD")).isEqualTo(-1025L);
        invariants.allHold();
    }

    /**
     * SCENARIO 6 — the connection drops while a refund is being recorded.
     *
     * <p>Attacks: the refund cap. The cap is derived by summing refund rows, so
     * a half-written refund would either consume capacity that was never
     * refunded or refund money without recording it.
     */
    @Test
    @DisplayName("S6: a connection drop mid-refund leaves the refundable amount untouched")
    void connectionDropMidRefundLeavesTheCapIntact() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        JsonNode payment = pay(payer, payee, 1000L, "USD");
        UUID paymentId = paymentIdOf(payment);

        refund(paymentId, 400L, "USD");
        assertThat(count("SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE payment_id = ?",
                paymentId)).isEqualTo(400L);

        LedgerInvariants.Snapshot before = invariants.snapshot();

        database.failOnce(sql -> sql.contains("insert into refunds"),
                ChaosDataSource.connectionDropped());

        assertThatThrownBy(() -> refund(paymentId, 600L, "USD")).isNotNull();
        assertThat(database.firedCount()).isEqualTo(1);
        database.healthy();

        invariants.nothingWasWrittenSince(before).allHold();
        assertThat(invariants.balanceMinorUnits(payer, "USD"))
                .as("the failed refund must not have moved money")
                .isEqualTo(-600L);

        // The capacity the failed refund would have used is still available,
        // which is the precise thing a half-written refund would have stolen.
        refund(paymentId, 600L, "USD");
        assertThat(count("SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE payment_id = ?",
                paymentId)).isEqualTo(1000L);
        assertThat(invariants.balanceMinorUnits(payer, "USD")).isZero();
        invariants.allHold();
    }

    /**
     * SCENARIO 7 — the statement times out part way through a reconciliation run.
     *
     * <p>Attacks: the run record and its incidents, which are written together.
     * A run row claiming it examined the ledger, with the incidents it found
     * missing, is worse than no run at all — it is a clean bill of health that
     * was never earned.
     */
    @Test
    @DisplayName("S7: a timeout mid-reconciliation records neither a run nor half its incidents")
    void timeoutMidReconciliationRecordsNothing() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");
        pay(payer, payee, 5000L, "USD");

        // Past the grace window, so the unsettled transaction is a real finding
        // rather than something the simulator has simply not reached yet.
        clock.advanceSeconds(60);

        LedgerInvariants.Snapshot before = invariants.snapshot();
        assertThat(before.reconciliationRuns()).isZero();

        database.failOnce(sql -> sql.contains("insert into reconciliation_incidents"),
                ChaosDataSource.statementTimeout());

        assertThatThrownBy(reconciliation::run)
                .as("a run that cannot record what it found must fail, not report success")
                .isNotNull();
        assertThat(database.firedCount()).isEqualTo(1);
        database.healthy();

        invariants.nothingWasWrittenSince(before);
        assertThat(count("SELECT COUNT(*) FROM reconciliation_runs"))
                .as("no run row may survive a run that could not finish")
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM reconciliation_incidents")).isZero();

        // Re-running after recovery produces the finding that was lost.
        ReconciliationService.RunResult result = reconciliation.run();
        assertThat(result.incidents())
                .as("the discrepancy the failed run would have reported is still found")
                .hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM reconciliation_runs")).isEqualTo(1);
        invariants.allHold();
    }

    /**
     * SCENARIO 7b — the connection stays broken for the whole write.
     *
     * <p>A one-shot fault lets the retry through immediately, which is the
     * optimistic case. This holds the failure open across every insert the
     * payment attempts, so nothing can slip past on a second try inside the same
     * transaction.
     */
    @Test
    @DisplayName("S7b: a connection that stays down blocks the write entirely and cleanly")
    void aPersistentlyBrokenConnectionWritesNothing() {
        UUID payer = createAccount("USD");
        UUID payee = createAccount("USD");

        LedgerInvariants.Snapshot before = invariants.snapshot();

        database.failAlways(sql -> sql.contains("insert into"), ChaosDataSource.connectionDropped());

        assertThatThrownBy(() -> pay(payer, payee, 777L, "USD")).isNotNull();
        assertThat(database.firedCount()).isGreaterThanOrEqualTo(1);

        database.healthy();
        invariants.nothingWasWrittenSince(before).allHold();
    }
}
