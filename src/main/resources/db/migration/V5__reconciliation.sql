-- LedgerGuard Phase 5: Settlement Simulator and Reconciliation
--
-- Phases 1 to 4 prove the ledger agrees WITH ITSELF: debits equal credits, no
-- transaction is half-written, no payment is duplicated. None of that says the
-- ledger agrees with anyone ELSE. A payment can be perfectly balanced
-- internally and still be $50 off what the processor actually settled.
--
-- These tables exist to detect that second kind of wrongness.

-- ---------------------------------------------------------------------------
-- The external world.
--
-- NOT a ledger table. These rows model a payment processor's records, which we
-- neither own nor control, so unlike postings they are MUTABLE and carry no
-- balance invariant. The fault-injection endpoint edits them precisely because
-- an external system can and does change its mind.
--
-- The simulator that fills this table consumes the Kafka events and never
-- reads a ledger table. That independence is the entire point: a simulator
-- that copied from `transactions` would make reconciliation compare the ledger
-- against a mirror of itself, and a comparison that cannot fail is not a
-- comparison.
-- ---------------------------------------------------------------------------
CREATE TABLE settlement_records (
    id                 UUID         PRIMARY KEY,

    -- The processor's own identifier for the movement.
    external_id        VARCHAR(100) NOT NULL UNIQUE,

    -- The processor's echo of OUR reference: the internal transaction id.
    -- This is the matching key. It is deliberately nullable, because an
    -- external record with no usable reference is a real scenario and
    -- classifies as UNEXPECTED_EXTERNAL_TRANSACTION rather than an error.
    external_reference VARCHAR(200),

    amount_minor       BIGINT       NOT NULL,
    currency           VARCHAR(3)   NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    settled_at         TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT settlement_amount_non_negative CHECK (amount_minor >= 0),
    CONSTRAINT settlement_currency_iso        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT settlement_status_valid        CHECK (status IN ('SETTLED', 'PENDING', 'FAILED'))
);

-- Not unique: two records sharing a reference is exactly DUPLICATE_SETTLEMENT,
-- which reconciliation must be able to see rather than the database prevent.
CREATE INDEX idx_settlement_reference ON settlement_records (external_reference);

-- ---------------------------------------------------------------------------
-- One reconciliation pass.
--
-- This is where MATCHED lives. A matched transaction produces no incident:
-- incidents are exceptions that need action, and a row per match would bury
-- the real ones. The counts here keep matches observable without the noise.
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_runs (
    id                UUID        PRIMARY KEY,
    started_at        TIMESTAMPTZ NOT NULL,
    completed_at      TIMESTAMPTZ NOT NULL,
    internal_examined INTEGER     NOT NULL,
    external_examined INTEGER     NOT NULL,
    matched           INTEGER     NOT NULL,
    discrepancies     INTEGER     NOT NULL,

    CONSTRAINT runs_counts_non_negative CHECK (
        internal_examined >= 0 AND external_examined >= 0
        AND matched >= 0 AND discrepancies >= 0)
);

-- ---------------------------------------------------------------------------
-- One disagreement between the two worlds.
--
-- transaction_id and settlement_record_id together are the EVIDENCE: they
-- point at the exact pair of records that were compared, so an investigator
-- never has to guess what the engine was looking at. Each is nullable because
-- two discrepancy types have only one side by definition:
--   MISSING_SETTLEMENT             has no external record
--   UNEXPECTED_EXTERNAL_TRANSACTION has no internal transaction
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_incidents (
    id                    UUID         PRIMARY KEY,
    run_id                UUID         NOT NULL REFERENCES reconciliation_runs(id),

    discrepancy_type      VARCHAR(40)  NOT NULL,
    severity              VARCHAR(10)  NOT NULL,
    status                VARCHAR(10)  NOT NULL,

    transaction_id        UUID,
    settlement_record_id  UUID,

    internal_amount_minor BIGINT,
    external_amount_minor BIGINT,
    difference_minor      BIGINT,
    currency              VARCHAR(3),

    internal_status       VARCHAR(20),
    external_status       VARCHAR(20),

    detail                TEXT         NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    resolved_at           TIMESTAMPTZ,

    CONSTRAINT incident_type_valid CHECK (discrepancy_type IN (
        'MATCHED', 'MISSING_SETTLEMENT', 'AMOUNT_MISMATCH',
        'DUPLICATE_SETTLEMENT', 'STATUS_MISMATCH', 'UNEXPECTED_EXTERNAL_TRANSACTION')),
    CONSTRAINT incident_severity_valid CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT incident_status_valid   CHECK (status IN ('OPEN', 'RESOLVED')),

    -- Every incident must point at something. An incident with neither side is
    -- not evidence of anything.
    CONSTRAINT incident_has_evidence CHECK (
        transaction_id IS NOT NULL OR settlement_record_id IS NOT NULL),

    CONSTRAINT incident_resolved_has_timestamp CHECK (
        (status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

CREATE INDEX idx_incidents_run      ON reconciliation_incidents (run_id);
CREATE INDEX idx_incidents_triage   ON reconciliation_incidents (status, severity, discrepancy_type);
CREATE INDEX idx_incidents_txn      ON reconciliation_incidents (transaction_id);
