-- LedgerGuard Phase 1: Ledger Core
--
-- Money is stored exclusively as integer minor units (BIGINT). There is no
-- NUMERIC/DECIMAL/DOUBLE money column anywhere in this schema by design.
-- $10.25 is stored as 1025. Conversion to/from BigDecimal happens only at the
-- HTTP boundary (see com.ledgerguard.config.Money).

CREATE TABLE accounts (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    currency   VARCHAR(3)   NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT accounts_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT accounts_currency_iso   CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE transactions (
    id          UUID         PRIMARY KEY,
    description VARCHAR(500) NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT transactions_description_not_blank CHECK (length(btrim(description)) > 0),
    CONSTRAINT transactions_currency_iso          CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE payments (
    id                     UUID        PRIMARY KEY,
    source_account_id      UUID        NOT NULL REFERENCES accounts(id),
    destination_account_id UUID        NOT NULL REFERENCES accounts(id),
    transaction_id         UUID        REFERENCES transactions(id),
    amount_minor           BIGINT      NOT NULL,
    currency               VARCHAR(3)  NOT NULL,
    status                 VARCHAR(16) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT payments_amount_positive   CHECK (amount_minor > 0),
    CONSTRAINT payments_accounts_distinct CHECK (source_account_id <> destination_account_id),
    CONSTRAINT payments_status_valid      CHECK (status IN ('PENDING', 'POSTED')),
    CONSTRAINT payments_currency_iso      CHECK (currency ~ '^[A-Z]{3}$')
);

-- Postings are append-only. Nothing in the application issues UPDATE or DELETE
-- against this table; see com.ledgerguard.postings.Posting (no setters, every
-- column mapped updatable=false) and PostingRepository (read/insert only).
CREATE TABLE postings (
    id             UUID        PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES transactions(id),
    account_id     UUID        NOT NULL REFERENCES accounts(id),
    type           VARCHAR(6)  NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT postings_type_valid       CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT postings_amount_positive  CHECK (amount_minor > 0),
    CONSTRAINT postings_currency_iso     CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_postings_account_currency ON postings (account_id, currency);
CREATE INDEX idx_postings_transaction      ON postings (transaction_id);
CREATE INDEX idx_payments_transaction      ON payments (transaction_id);
