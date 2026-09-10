-- LedgerGuard Phase 2: Refunds, Reversals and Transaction Safety
--
-- Neither table stores money that moves. Money still moves only through
-- postings, written by TransactionService after the balance check passes.
-- These tables record why a transaction exists and what it points back at.
--
-- Phase 1 tables are not altered. The links live here.

-- A refund of all or part of a payment. The refund never edits the original
-- payment or its postings; it records that a NEW balanced transaction was
-- written in the opposite direction.
--
-- The cumulative refunded amount for a payment is derived by summing
-- amount_minor across these rows, the same way an account balance is derived
-- by summing postings. Payment has no refunded_total column to drift.
CREATE TABLE refunds (
    id             UUID        PRIMARY KEY,
    payment_id     UUID        NOT NULL REFERENCES payments(id),
    transaction_id UUID        NOT NULL UNIQUE REFERENCES transactions(id),
    amount_minor   BIGINT      NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT refunds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT refunds_currency_iso    CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_refunds_payment ON refunds (payment_id);

-- A full reversal of a transaction: a new transaction whose postings exactly
-- negate the original.
--
-- The UNIQUE constraint on original_transaction_id is the reverse-only-once
-- rule, enforced by the database rather than only by the service. Two
-- concurrent reversal attempts cannot both succeed: the second INSERT fails.
CREATE TABLE reversals (
    id                      UUID        PRIMARY KEY,
    original_transaction_id UUID        NOT NULL UNIQUE REFERENCES transactions(id),
    reversal_transaction_id UUID        NOT NULL UNIQUE REFERENCES transactions(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT reversals_distinct CHECK (original_transaction_id <> reversal_transaction_id)
);

-- NOTE ON THE REFUND CAP
-- The rule "SUM(refunds.amount_minor) <= payments.amount_minor" spans multiple
-- rows, and a CHECK constraint can only see the row being written. It is
-- therefore enforced in RefundService, which takes a PESSIMISTIC_WRITE lock on
-- the payment row first so two concurrent refunds cannot both read the same
-- remaining balance and both succeed.
--
-- This mirrors the Phase 1 decision to keep the cross-row rule
-- (SUM(debits) = SUM(credits)) in TransactionService rather than splitting it
-- into a trigger.
