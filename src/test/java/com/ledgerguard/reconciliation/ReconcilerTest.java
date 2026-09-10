package com.ledgerguard.reconciliation;

import com.ledgerguard.reconciliation.Reconciler.Comparison;
import com.ledgerguard.reconciliation.Reconciler.ExternalRecord;
import com.ledgerguard.reconciliation.Reconciler.InternalTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classification rules, tested without Spring or a database.
 *
 * <p>Deciding what counts as a discrepancy is the interesting part of this
 * phase, so it is proved here in isolation. Every case below is a hand-built
 * pair of records, which makes the edge cases — a one-cent difference, a
 * zero-difference status disagreement, a reference pointing at nothing —
 * expressible directly rather than staged through the whole stack.
 */
class ReconcilerTest {

    private static final String USD = "USD";

    private final Reconciler reconciler = new Reconciler();

    private final UUID txnId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();

    private InternalTransaction internal(long amountMinor) {
        return new InternalTransaction(txnId, amountMinor, USD);
    }

    private ExternalRecord external(long amountMinor, String status) {
        return new ExternalRecord(recordId, "SIM-1", txnId.toString(), amountMinor, USD, status);
    }

    private Comparison only(List<InternalTransaction> internal, List<ExternalRecord> external) {
        List<Comparison> results = reconciler.reconcile(internal, external);
        assertThat(results).as("each comparison must produce exactly one classification").hasSize(1);
        return results.get(0);
    }

    // ---------- the six outcomes ----------

    @Test
    @DisplayName("MATCHED when amount, currency and status all agree")
    void matched() {
        Comparison result = only(List.of(internal(25_000L)), List.of(external(25_000L, "SETTLED")));

        assertThat(result.type()).isEqualTo(DiscrepancyType.MATCHED);
        assertThat(result.type().isDiscrepancy()).as("a match is not an incident").isFalse();
        assertThat(result.differenceMinor()).isZero();
        assertThat(result.transactionId()).isEqualTo(txnId);
        assertThat(result.settlementRecordId()).isEqualTo(recordId);
    }

    @Test
    @DisplayName("MISSING_SETTLEMENT when we recorded it and the processor has nothing")
    void missingSettlement() {
        Comparison result = only(List.of(internal(25_000L)), List.of());

        assertThat(result.type()).isEqualTo(DiscrepancyType.MISSING_SETTLEMENT);
        assertThat(result.settlementRecordId()).as("there is no external side to point at").isNull();
        assertThat(result.transactionId()).isEqualTo(txnId);
        assertThat(result.differenceMinor())
                .as("the whole amount is in question, not part of it")
                .isEqualTo(25_000L);
    }

    @Test
    @DisplayName("AMOUNT_MISMATCH: we recorded $250, they settled $200")
    void amountMismatch() {
        Comparison result = only(List.of(internal(25_000L)), List.of(external(20_000L, "SETTLED")));

        assertThat(result.type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(result.internalAmountMinor()).isEqualTo(25_000L);
        assertThat(result.externalAmountMinor()).isEqualTo(20_000L);
        assertThat(result.differenceMinor()).isEqualTo(5_000L);
        assertThat(result.detail()).contains("difference 5000");
    }

    @Test
    @DisplayName("DUPLICATE_SETTLEMENT when two external records share one reference")
    void duplicateSettlement() {
        ExternalRecord first = external(25_000L, "SETTLED");
        ExternalRecord second = new ExternalRecord(
                UUID.randomUUID(), "SIM-2", txnId.toString(), 25_000L, USD, "SETTLED");

        Comparison result = only(List.of(internal(25_000L)), List.of(first, second));

        assertThat(result.type()).isEqualTo(DiscrepancyType.DUPLICATE_SETTLEMENT);
        assertThat(result.differenceMinor())
                .as("exposure is the extra copy, not the whole amount: one of them was meant to happen")
                .isEqualTo(25_000L);
        assertThat(result.detail()).contains("SIM-1", "SIM-2").contains("2 external records");
    }

    @Test
    @DisplayName("STATUS_MISMATCH when the money agrees but the state does not")
    void statusMismatch() {
        Comparison result = only(List.of(internal(25_000L)), List.of(external(25_000L, "PENDING")));

        assertThat(result.type()).isEqualTo(DiscrepancyType.STATUS_MISMATCH);
        assertThat(result.internalStatus()).isEqualTo("POSTED");
        assertThat(result.externalStatus()).isEqualTo("PENDING");
        assertThat(result.differenceMinor()).isZero();
    }

    @Test
    @DisplayName("UNEXPECTED_EXTERNAL_TRANSACTION when money moves that we never authorised")
    void unexpectedExternal() {
        ExternalRecord orphan = new ExternalRecord(
                recordId, "SIM-PHANTOM", UUID.randomUUID().toString(), 9_900L, USD, "SETTLED");

        Comparison result = only(List.of(), List.of(orphan));

        assertThat(result.type()).isEqualTo(DiscrepancyType.UNEXPECTED_EXTERNAL_TRANSACTION);
        assertThat(result.transactionId()).as("there is no internal side to point at").isNull();
        assertThat(result.settlementRecordId()).isEqualTo(recordId);
        assertThat(result.detail()).contains("matches no internal transaction");
    }

    // ---------- edge cases ----------

    @Nested
    @DisplayName("edge cases")
    class Edges {

        @Test
        @DisplayName("a one-cent difference is still an AMOUNT_MISMATCH")
        void onePennyStillCounts() {
            Comparison result = only(List.of(internal(25_000L)), List.of(external(24_999L, "SETTLED")));

            assertThat(result.type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
            assertThat(result.differenceMinor()).isEqualTo(1L);
            assertThat(result.severity())
                    .as("small, but a rounding bug looks exactly like this before it scales")
                    .isEqualTo(Severity.MEDIUM);
        }

        @Test
        @DisplayName("a zero difference with a status disagreement is STILL flagged")
        void zeroDifferenceStatusMismatchIsFlagged() {
            Comparison result = only(List.of(internal(25_000L)), List.of(external(25_000L, "FAILED")));

            assertThat(result.type())
                    .as("amounts agreeing is what makes this easy to miss, not a reason to ignore it")
                    .isEqualTo(DiscrepancyType.STATUS_MISMATCH);
            assertThat(result.type().isDiscrepancy()).isTrue();
            assertThat(result.differenceMinor()).isZero();
            assertThat(result.severity())
                    .as("no money in question yet, so it sits at the type floor")
                    .isEqualTo(Severity.LOW);
        }

        @Test
        @DisplayName("when both amount and status differ, amount wins")
        void amountTakesPrecedenceOverStatus() {
            Comparison result = only(List.of(internal(25_000L)), List.of(external(20_000L, "FAILED")));

            assertThat(result.type())
                    .as("exactly one classification is allowed, and the money is the more actionable fact")
                    .isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
            assertThat(result.externalStatus())
                    .as("the status is still captured as evidence even though it did not decide the type")
                    .isEqualTo("FAILED");
        }

        @Test
        @DisplayName("a differing currency is an AMOUNT_MISMATCH, not a silent match")
        void differingCurrencyIsAMismatch() {
            ExternalRecord eur = new ExternalRecord(recordId, "SIM-1", txnId.toString(), 25_000L, "EUR", "SETTLED");

            Comparison result = only(List.of(internal(25_000L)), List.of(eur));

            assertThat(result.type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
            assertThat(result.detail()).contains("currency disagreement", "not comparable");
        }

        @Test
        @DisplayName("a blank external reference is unmatched, not dropped")
        void blankReferenceIsUnexpected() {
            ExternalRecord blank = new ExternalRecord(recordId, "SIM-BLANK", "  ", 5_000L, USD, "SETTLED");

            Comparison result = only(List.of(), List.of(blank));

            assertThat(result.type()).isEqualTo(DiscrepancyType.UNEXPECTED_EXTERNAL_TRANSACTION);
            assertThat(result.detail()).contains("no usable reference");
        }

        @Test
        @DisplayName("a null external reference is treated the same way")
        void nullReferenceIsUnexpected() {
            ExternalRecord nullRef = new ExternalRecord(recordId, "SIM-NULL", null, 5_000L, USD, "SETTLED");

            assertThat(only(List.of(), List.of(nullRef)).type())
                    .isEqualTo(DiscrepancyType.UNEXPECTED_EXTERNAL_TRANSACTION);
        }

        @Test
        @DisplayName("every internal transaction and every unmatched external record is classified once")
        void nothingIsCountedTwiceOrDropped() {
            UUID otherTxn = UUID.randomUUID();
            ExternalRecord orphan = new ExternalRecord(
                    UUID.randomUUID(), "SIM-ORPHAN", UUID.randomUUID().toString(), 100L, USD, "SETTLED");

            List<Comparison> results = reconciler.reconcile(
                    List.of(internal(25_000L), new InternalTransaction(otherTxn, 100L, USD)),
                    List.of(external(25_000L, "SETTLED"), orphan));

            assertThat(results).hasSize(3);
            assertThat(results).extracting(Comparison::type).containsExactlyInAnyOrder(
                    DiscrepancyType.MATCHED,
                    DiscrepancyType.MISSING_SETTLEMENT,
                    DiscrepancyType.UNEXPECTED_EXTERNAL_TRANSACTION);
        }

        @Test
        @DisplayName("a matched external record is not also reported as unexpected")
        void matchedRecordIsNotDoubleReported() {
            List<Comparison> results = reconciler.reconcile(
                    List.of(internal(25_000L)), List.of(external(25_000L, "SETTLED")));

            assertThat(results).extracting(Comparison::type)
                    .doesNotContain(DiscrepancyType.UNEXPECTED_EXTERNAL_TRANSACTION);
        }
    }

    // ---------- severity ----------

    @Nested
    @DisplayName("severity: type sets the floor, amount escalates")
    class Severities {

        @Test
        @DisplayName("$100 in question escalates to HIGH")
        void hundredDollarsEscalatesToHigh() {
            Comparison result = only(List.of(internal(35_000L)), List.of(external(25_000L, "SETTLED")));

            assertThat(result.differenceMinor()).isEqualTo(10_000L);
            assertThat(result.severity()).isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("$1,000 in question escalates to CRITICAL")
        void thousandDollarsEscalatesToCritical() {
            Comparison result = only(List.of(internal(125_000L)), List.of(external(25_000L, "SETTLED")));

            assertThat(result.differenceMinor()).isEqualTo(100_000L);
            assertThat(result.severity()).isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("a trivial duplicate is still HIGH, because a control failed")
        void duplicateNeverDeEscalates() {
            ExternalRecord second = new ExternalRecord(UUID.randomUUID(), "SIM-2", txnId.toString(), 1L, USD, "SETTLED");

            Comparison result = only(List.of(internal(1L)), List.of(external(1L, "SETTLED"), second));

            assertThat(result.differenceMinor()).isEqualTo(1L);
            assertThat(result.severity())
                    .as("one cent, but somebody was paid twice and nothing stopped it")
                    .isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("a trivial phantom settlement is still HIGH")
        void unexpectedNeverDeEscalates() {
            ExternalRecord tiny = new ExternalRecord(
                    recordId, "SIM-TINY", UUID.randomUUID().toString(), 1L, USD, "SETTLED");

            assertThat(only(List.of(), List.of(tiny)).severity()).isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("a large missing settlement escalates above its MEDIUM floor")
        void missingEscalatesWithAmount() {
            assertThat(only(List.of(internal(500_000L)), List.of()).severity()).isEqualTo(Severity.CRITICAL);
            assertThat(only(List.of(internal(500L)), List.of()).severity()).isEqualTo(Severity.MEDIUM);
        }

        @Test
        @DisplayName("escalation never lowers a floor")
        void escalationOnlyRaises() {
            assertThat(Severity.escalateFor(Severity.HIGH, 0L)).isEqualTo(Severity.HIGH);
            assertThat(Severity.escalateFor(Severity.HIGH, 10_000L)).isEqualTo(Severity.HIGH);
            assertThat(Severity.escalateFor(Severity.LOW, 1L)).isEqualTo(Severity.LOW);
            assertThat(Severity.escalateFor(Severity.CRITICAL, 1L)).isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("a negative difference escalates on magnitude, not sign")
        void negativeDifferenceEscalatesOnMagnitude() {
            // The processor settled MORE than we recorded.
            Comparison result = only(List.of(internal(25_000L)), List.of(external(150_000L, "SETTLED")));

            assertThat(result.differenceMinor()).isNegative();
            assertThat(result.severity()).isEqualTo(Severity.CRITICAL);
        }
    }
}
