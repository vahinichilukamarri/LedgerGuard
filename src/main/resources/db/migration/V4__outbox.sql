-- LedgerGuard Phase 4: Transactional Outbox
--
-- The problem: publishing an event about a payment involves two systems that
-- cannot commit together. Send inside the transaction and Kafka may succeed
-- while Postgres rolls back, announcing a payment that does not exist. Send
-- after commit and the process may die first, so the payment exists and nobody
-- downstream ever hears about it.
--
-- The outbox removes the choice. The event is written to Postgres in the SAME
-- transaction as the ledger rows, so it is exactly as durable as the payment
-- itself. A separate poller moves it to Kafka afterwards.

CREATE TABLE outbox_events (
    id               UUID         PRIMARY KEY,

    -- What this event is about. aggregate_id doubles as the Kafka message key,
    -- so every event for one payment lands in one partition and stays ordered.
    aggregate_type   VARCHAR(50)  NOT NULL,
    aggregate_id     UUID         NOT NULL,
    event_type       VARCHAR(50)  NOT NULL,

    topic            VARCHAR(200) NOT NULL,

    -- The JSON envelope. TEXT rather than JSONB because the publisher never
    -- inspects it; it moves bytes. JSONB would buy queryability nothing needs.
    payload          TEXT         NOT NULL,

    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- NULL means not yet on Kafka. This is the only column that ever changes.
    published_at     TIMESTAMPTZ,
    publish_attempts INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT outbox_event_type_valid CHECK (event_type IN
        ('PaymentPosted', 'PaymentRefunded', 'TransactionReversed')),
    CONSTRAINT outbox_attempts_non_negative CHECK (publish_attempts >= 0)
);

-- Partial index: the poller only ever asks for unpublished rows, and this index
-- stays small no matter how large the table grows.
CREATE INDEX idx_outbox_unpublished ON outbox_events (occurred_at)
    WHERE published_at IS NULL;

-- Consumer-side deduplication.
--
-- Delivery here is AT-LEAST-ONCE, not exactly-once. If the publisher dies after
-- sending to Kafka but before marking the row published, it will send again on
-- restart. That duplicate is unavoidable: marking before sending would trade it
-- for a lost event, which is worse.
--
-- So consumers must tolerate seeing the same event twice. event_id is the
-- outbox row id, which is stable across republishes, and the composite primary
-- key below is what makes a second delivery a no-op.
CREATE TABLE processed_events (
    consumer_name VARCHAR(100) NOT NULL,
    event_id      UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (consumer_name, event_id)
);
