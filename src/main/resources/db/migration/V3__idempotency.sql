-- LedgerGuard Phase 3: Idempotency and Safe Retries
--
-- The problem: a client POSTs a payment, the connection drops before the
-- response arrives, the client retries. Phases 1 and 2 would happily create a
-- second payment and move the money twice.
--
-- This table is NOT a ledger record. Unlike postings, refunds and reversals,
-- its rows are updated after insert (the response is recorded once the work
-- finishes). It records what the API already answered, not what the money did.

CREATE TABLE idempotency_keys (
    id                  UUID         PRIMARY KEY,

    -- Client-supplied token, and the route template it was used against.
    -- Scoping by endpoint means the same token on /payments and on a refund
    -- are unrelated requests rather than a collision.
    idempotency_key     VARCHAR(255) NOT NULL,
    endpoint            VARCHAR(200) NOT NULL,

    -- SHA-256 of method + concrete path + canonical request body, so that
    -- reusing a key for a DIFFERENT request is detected rather than assumed
    -- away. Canonical means object keys sorted recursively, so JSON key order
    -- cannot change the fingerprint.
    request_fingerprint VARCHAR(64)  NOT NULL,

    -- Recorded once the handler has produced its answer. Nullable only for the
    -- window inside the writing transaction, which never becomes visible to
    -- anyone: the row and the ledger writes commit together or not at all.
    response_status     INTEGER,
    response_body       TEXT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Written but never read. It exists now because adding a retention column
    -- to an already-populated table later means backfilling every row; the
    -- sweeper that acts on it belongs to a later phase.
    expires_at          TIMESTAMPTZ  NOT NULL,

    -- THE concurrency guarantee. Two simultaneous requests carrying the same
    -- key against the same endpoint cannot both insert. The loser blocks on
    -- this index until the winner commits, then fails, rolls back without
    -- having touched the ledger, and replays the winner's stored response.
    -- Enforced by the database, not only by the service, for the same reason
    -- reversals.original_transaction_id is UNIQUE.
    CONSTRAINT idempotency_keys_unique     UNIQUE (endpoint, idempotency_key),

    CONSTRAINT idempotency_key_not_blank   CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT idempotency_fingerprint_hex CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_status_valid    CHECK (response_status BETWEEN 100 AND 599),
    CONSTRAINT idempotency_expires_after   CHECK (expires_at > created_at)
);

CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);
