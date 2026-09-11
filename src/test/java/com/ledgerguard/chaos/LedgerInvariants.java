package com.ledgerguard.chaos;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What must be true of the ledger after any fault, expressed once.
 *
 * <h2>Why these live together</h2>
 *
 * A chaos scenario is only as good as the question it asks afterwards. Left to
 * itself, each scenario tends to assert the one thing its author was thinking
 * about — "the payment rolled back" — and quietly miss that the fault also left
 * an outbox row pointing at a payment that no longer exists. Naming the
 * invariants in one place and calling them from every scenario makes the weaker
 * question impossible to ask by accident.
 *
 * <p>Every assertion here reads the database directly rather than going through
 * a repository. After a fault, the repositories and the persistence context are
 * exactly what is under suspicion.
 */
public final class LedgerInvariants {

    private final JdbcTemplate jdbc;

    public LedgerInvariants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------ the invariants

    /**
     * The Phase 1 invariant, per transaction and per currency. No fault may
     * leave a transaction half-written.
     */
    public LedgerInvariants everyTransactionBalancesPerCurrency() {
        List<Map<String, Object>> unbalanced = jdbc.queryForList("""
                SELECT transaction_id, currency,
                       SUM(CASE WHEN type = 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS net
                FROM postings
                GROUP BY transaction_id, currency
                HAVING SUM(CASE WHEN type = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
                """);

        assertThat(unbalanced)
                .as("every persisted transaction must net to zero in every currency")
                .isEmpty();
        return this;
    }

    /** Every posting ever written, summed. Always zero. */
    public LedgerInvariants ledgerNetsToZero() {
        assertThat(queryLong("""
                SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
                FROM postings
                """))
                .as("the whole ledger must net to zero")
                .isZero();
        return this;
    }

    /**
     * No transaction row without postings, and no posting without a transaction.
     *
     * <p>This is the shape a fault between the two INSERTs would leave behind,
     * and it is invisible to the balance check: a transaction with no postings
     * has nothing to be unbalanced about.
     */
    public LedgerInvariants noOrphanedTransactionsOrPostings() {
        assertThat(queryLong("""
                SELECT COUNT(*) FROM transactions t
                WHERE NOT EXISTS (SELECT 1 FROM postings p WHERE p.transaction_id = t.id)
                """))
                .as("a transaction with no postings means a write was interrupted between the two")
                .isZero();

        assertThat(queryLong("""
                SELECT COUNT(*) FROM postings p
                WHERE NOT EXISTS (SELECT 1 FROM transactions t WHERE t.id = p.transaction_id)
                """))
                .as("a posting with no transaction should be impossible; the FK should have refused it")
                .isZero();
        return this;
    }

    /**
     * Every outbox row describes something that actually committed.
     *
     * <p>The dual-write failure stated as a query. An event announcing a payment
     * that does not exist is the exact outcome the transactional outbox is built
     * to prevent, and no fault may produce one.
     */
    public LedgerInvariants noOutboxRowDescribesSomethingThatNeverCommitted() {
        assertThat(queryLong("""
                SELECT COUNT(*) FROM outbox_events e
                WHERE e.aggregate_type = 'Payment'
                  AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.id = e.aggregate_id)
                """))
                .as("an outbox event about a payment that does not exist is a dual-write failure")
                .isZero();

        assertThat(queryLong("""
                SELECT COUNT(*) FROM outbox_events e
                WHERE e.aggregate_type = 'Transaction'
                  AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.id = e.aggregate_id)
                """))
                .as("an outbox event about a transaction that does not exist is a dual-write failure")
                .isZero();
        return this;
    }

    /**
     * The converse, which is the more dangerous direction: money moved and
     * nothing downstream will ever hear about it.
     *
     * <p>A committed payment with no outbox row is a permanently lost event. No
     * retry recovers it, because nothing records that it should exist.
     */
    public LedgerInvariants everyPostedPaymentHasAnEvent() {
        assertThat(queryLong("""
                SELECT COUNT(*) FROM payments p
                WHERE p.status IN ('POSTED', 'REVERSED')
                  AND NOT EXISTS (
                      SELECT 1 FROM outbox_events e
                      WHERE e.aggregate_id = p.id AND e.event_type = 'PaymentPosted')
                """))
                .as("a committed payment with no outbox event is an event lost forever")
                .isZero();
        return this;
    }

    /** One claim per event id, whatever the delivery pattern was. */
    public LedgerInvariants everyEventWasActedOnAtMostOnce() {
        List<Map<String, Object>> repeated = jdbc.queryForList("""
                SELECT event_id, COUNT(*) AS claims
                FROM processed_events
                GROUP BY consumer_name, event_id
                HAVING COUNT(*) > 1
                """);

        assertThat(repeated)
                .as("at-least-once delivery must not become at-least-once effects")
                .isEmpty();
        return this;
    }

    /** No payment may be refunded past its own amount, whatever the fault did. */
    public LedgerInvariants refundsNeverExceedTheirPayment() {
        List<Map<String, Object>> over = jdbc.queryForList("""
                SELECT p.id, p.amount_minor, COALESCE(SUM(r.amount_minor), 0) AS refunded
                FROM payments p
                LEFT JOIN refunds r ON r.payment_id = p.id
                GROUP BY p.id, p.amount_minor
                HAVING COALESCE(SUM(r.amount_minor), 0) > p.amount_minor
                """);

        assertThat(over)
                .as("refunds may never sum past the payment they refund")
                .isEmpty();
        return this;
    }

    /** A reversal is single-use; two for one transaction would double the negation. */
    public LedgerInvariants everyTransactionReversedAtMostOnce() {
        List<Map<String, Object>> repeated = jdbc.queryForList("""
                SELECT original_transaction_id, COUNT(*) AS reversals
                FROM reversals
                GROUP BY original_transaction_id
                HAVING COUNT(*) > 1
                """);

        assertThat(repeated).as("a transaction may be reversed at most once").isEmpty();
        return this;
    }

    /**
     * The simulator derives its external id from the event id, so a redelivery
     * collides instead of inventing a second settlement. Two records for one
     * event would be a bug in the simulator masquerading as a ledger discrepancy.
     */
    public LedgerInvariants noSettlementRecordDuplicatesAnEvent() {
        List<Map<String, Object>> repeated = jdbc.queryForList("""
                SELECT external_id, COUNT(*) AS records
                FROM settlement_records
                GROUP BY external_id
                HAVING COUNT(*) > 1
                """);

        assertThat(repeated)
                .as("one event must never produce two settlement records")
                .isEmpty();
        return this;
    }

    /** Everything above, for a scenario that has no reason to check less. */
    public LedgerInvariants allHold() {
        return everyTransactionBalancesPerCurrency()
                .ledgerNetsToZero()
                .noOrphanedTransactionsOrPostings()
                .noOutboxRowDescribesSomethingThatNeverCommitted()
                .everyPostedPaymentHasAnEvent()
                .everyEventWasActedOnAtMostOnce()
                .refundsNeverExceedTheirPayment()
                .everyTransactionReversedAtMostOnce()
                .noSettlementRecordDuplicatesAnEvent();
    }

    // --------------------------------------------------------- snapshots

    /**
     * Every count that a write would move, plus the ledger net.
     *
     * <p>"The fault left nothing behind" is only convincing if it is checked
     * against everything a partial write could have touched, rather than against
     * the one table the scenario had in mind.
     */
    public record Snapshot(long accounts, long payments, long transactions, long postings,
                           long refunds, long reversals, long outboxEvents, long unpublishedOutbox,
                           long processedEvents, long settlementRecords,
                           long reconciliationRuns, long reconciliationIncidents,
                           long idempotencyKeys, long ledgerNet) {
    }

    public Snapshot snapshot() {
        return new Snapshot(
                queryLong("SELECT COUNT(*) FROM accounts"),
                queryLong("SELECT COUNT(*) FROM payments"),
                queryLong("SELECT COUNT(*) FROM transactions"),
                queryLong("SELECT COUNT(*) FROM postings"),
                queryLong("SELECT COUNT(*) FROM refunds"),
                queryLong("SELECT COUNT(*) FROM reversals"),
                queryLong("SELECT COUNT(*) FROM outbox_events"),
                queryLong("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"),
                queryLong("SELECT COUNT(*) FROM processed_events"),
                queryLong("SELECT COUNT(*) FROM settlement_records"),
                queryLong("SELECT COUNT(*) FROM reconciliation_runs"),
                queryLong("SELECT COUNT(*) FROM reconciliation_incidents"),
                queryLong("SELECT COUNT(*) FROM idempotency_keys"),
                queryLong("""
                        SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor
                                                 ELSE -amount_minor END), 0) FROM postings
                        """));
    }

    /** Nothing at all was written since {@code before}. */
    public LedgerInvariants nothingWasWrittenSince(Snapshot before) {
        assertThat(snapshot())
                .as("a refused or interrupted operation must leave the database exactly as it was")
                .isEqualTo(before);
        return this;
    }

    public long balanceMinorUnits(java.util.UUID accountId, String currency) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
                FROM postings WHERE account_id = ? AND currency = ?
                """, Long.class, accountId, currency);
        return value == null ? 0L : value;
    }

    private long queryLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
