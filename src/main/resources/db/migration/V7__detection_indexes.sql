-- Phase 8: read paths for the statistical detection layer.
--
-- Indexes only. No new tables, no new columns, no write path. The detection
-- layer computes every signal at query time from the ledger rows that already
-- exist, for the same reason account balances are derived from postings rather
-- than stored: an aggregate kept alongside the ledger can drift from it, and a
-- detector that disagrees with the ledger is worse than no detector.
--
-- What these support: per-account payment history, which every signal needs and
-- which was previously a sequential scan. `payments` had an index on
-- transaction_id only, because Phases 1-7 never looked a payment up by the
-- accounts on either end of it.
--
-- Both are composite on (account, created_at) rather than on the account alone.
-- Every detection query is "this account's payments, in time order, within a
-- window", so the timestamp belongs in the index; with it, the window is a range
-- scan and the ordering is free.

CREATE INDEX idx_payments_source_created
    ON payments (source_account_id, created_at);

CREATE INDEX idx_payments_destination_created
    ON payments (destination_account_id, created_at);

-- Reconciliation incidents are joined back to accounts through their
-- transaction, and the mismatch-rate signal filters them by time.
CREATE INDEX idx_incidents_created
    ON reconciliation_incidents (created_at);
