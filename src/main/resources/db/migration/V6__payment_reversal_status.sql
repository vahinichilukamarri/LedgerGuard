-- Phase 6 fix: a payment whose transaction has been reversed is REVERSED.
--
-- Before this, reversing a payment's transaction returned the money to the
-- payer but left the payment POSTED with its full refundable amount intact, so
-- the payment could then also be refunded — returning the money twice. Property
-- testing found it with a two-step chain: pay one minor unit, refund it, reverse
-- the payment. See the bug log in README.md.
--
-- The status column already exists and is already NOT NULL; only the set of
-- values it may hold changes. No backfill is needed: no existing row can be
-- REVERSED, because nothing could set that value before now.

ALTER TABLE payments DROP CONSTRAINT payments_status_valid;

ALTER TABLE payments ADD CONSTRAINT payments_status_valid
    CHECK (status IN ('PENDING', 'POSTED', 'REVERSED'));
