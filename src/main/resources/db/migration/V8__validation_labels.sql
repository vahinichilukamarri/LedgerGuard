-- LedgerGuard Phase 12: Labels, and the machinery to validate scores against them
--
-- Phases 8 through 11 all end with the same sentence in their reports: these
-- scores are unvalidated, because there is no labelled data. This migration is
-- where labelled data starts existing.
--
-- It is also the detection layer's FIRST WRITE PATH. Phases 8 to 11 were
-- read-only by construction and said so loudly. That property is not being
-- abandoned: nothing here writes a score, a signal, a feature or a model
-- output. What gets written is what a human or an external party said about an
-- account, which is evidence ABOUT the detector rather than output FROM it, and
-- keeping those two apart is the whole reason the detector can be evaluated at
-- all.

-- ---------------------------------------------------------------------------
-- The external world's opinion: disputes.
--
-- NOT a ledger table, and mutable for the same reason `settlement_records` is:
-- we do not own it. A cardholder disputes a payment weeks after it settled, a
-- bank reclassifies a reason code, a dispute is withdrawn. Modelling that
-- faithfully is what makes the resulting labels worth anything.
--
-- The simulator that fills this table consumes Kafka and never reads a ledger
-- table, exactly as the Phase 5 settlement simulator does, and for exactly the
-- same reason: a label source that derived itself from the data being judged
-- would be a mirror, and a comparison against a mirror cannot fail.
-- ---------------------------------------------------------------------------
CREATE TABLE disputes (
    id                 UUID         PRIMARY KEY,

    -- The scheme's own identifier for the dispute.
    external_id        VARCHAR(100) NOT NULL UNIQUE,

    -- The scheme's echo of our transaction id. Nullable: a dispute we cannot
    -- attribute to a transaction is a real event, and it produces no label
    -- rather than a guessed one.
    transaction_reference VARCHAR(200),

    -- NOT every chargeback is a fraud signal, and treating them alike is the
    -- most common way a fraud label set gets quietly poisoned. A cardholder who
    -- never received their goods has a dispute with a merchant, not evidence
    -- that the payment was fraudulent. Only FRAUDULENT becomes an ANOMALOUS
    -- label; the rest are recorded and deliberately not used as labels.
    reason             VARCHAR(40)  NOT NULL,

    amount_minor       BIGINT       NOT NULL,
    currency           VARCHAR(3)   NOT NULL,

    -- When the payment happened, and when the dispute was raised. The gap
    -- between them is the label latency that makes evaluation hard: the
    -- detector scored this account on the first date and the truth arrived on
    -- the second.
    payment_at         TIMESTAMPTZ,
    raised_at          TIMESTAMPTZ  NOT NULL,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT dispute_amount_non_negative CHECK (amount_minor >= 0),
    CONSTRAINT dispute_currency_iso        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT dispute_reason_valid        CHECK (reason IN (
        'FRAUDULENT', 'NOT_RECEIVED', 'DUPLICATE', 'AUTHORISATION', 'OTHER'))
);

CREATE INDEX idx_dispute_reference ON disputes (transaction_reference);
CREATE INDEX idx_dispute_raised_at ON disputes (raised_at);

-- ---------------------------------------------------------------------------
-- What somebody says about an account.
--
-- APPEND-ONLY, like postings and for a related reason. A reviewer who changes
-- their mind produces a second row; the first is not edited away. Three things
-- depend on that:
--
--   * two reviewers disagreeing is measurable, and inter-reviewer disagreement
--     is a CEILING on any accuracy figure this system can honestly report. If
--     humans disagree on a fifth of accounts, no detector is "95% accurate";
--   * a label set that silently changed under an evaluation would make that
--     evaluation unreproducible, which is the same argument Phase 8 makes for
--     deriving scores at query time rather than storing them;
--   * a reviewer revising a verdict after seeing a score is exactly the
--     contamination this table is designed to expose, and it can only be seen
--     if both verdicts survive.
-- ---------------------------------------------------------------------------
CREATE TABLE account_labels (
    id              UUID         PRIMARY KEY,
    account_id      UUID         NOT NULL REFERENCES accounts (id),

    -- ANOMALOUS, BENIGN or UNCLEAR. The third is not a failure to answer: an
    -- account a careful reviewer cannot judge is a fact about how hard the
    -- problem is, and forcing a binary would manufacture agreement.
    verdict         VARCHAR(20)  NOT NULL,

    -- HUMAN_REVIEW, DISPUTE_FEED or SYNTHETIC. These are three different
    -- epistemic objects and must never be pooled by accident -- a synthetic
    -- label contributing silently to a number someone quotes as "precision" is
    -- the precise failure this column exists to prevent.
    source          VARCHAR(20)  NOT NULL,

    -- FLAGGED or AUDIT, for human review only.
    --
    -- THE RECALL DENOMINATOR. If reviewers only ever label accounts the
    -- detector surfaced, then every anomalous account it missed is invisible,
    -- and recall is not merely inaccurate but structurally unmeasurable. The
    -- AUDIT stratum is a random sample of accounts the detector did NOT flag,
    -- and it is the only thing that makes a recall figure mean anything. It
    -- cannot be retrofitted onto a label set collected without it.
    stratum         VARCHAR(20),

    reviewer        VARCHAR(100),

    -- Whether the reviewer could see the scores when they judged.
    --
    -- A reviewer shown "this account scores 0.87" before deciding is not
    -- producing an independent label; they are producing an opinion about the
    -- detector's opinion, and evaluating a detector against those inflates
    -- every figure. Recorded per label rather than assumed, so contaminated and
    -- blind labels can be reported apart.
    scores_visible  BOOLEAN      NOT NULL,

    -- The instant in LEDGER time this label describes. Scores must be computed
    -- as of this moment and no later.
    labelled_as_of  TIMESTAMPTZ  NOT NULL,

    -- When the label became known. For a dispute this is weeks after
    -- labelled_as_of, and the difference is not bookkeeping: evaluating a
    -- score computed from data the labeller could not have had is temporal
    -- leakage, and it is the other classic way these numbers get inflated.
    observed_at     TIMESTAMPTZ  NOT NULL,

    -- The dispute, payment or review that produced this label.
    evidence_id     UUID,

    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT label_verdict_valid CHECK (verdict IN ('ANOMALOUS', 'BENIGN', 'UNCLEAR')),
    CONSTRAINT label_source_valid  CHECK (source IN ('HUMAN_REVIEW', 'DISPUTE_FEED', 'SYNTHETIC')),
    CONSTRAINT label_stratum_valid CHECK (stratum IS NULL OR stratum IN ('FLAGGED', 'AUDIT')),

    -- A human label with no reviewer and no stratum cannot be weighted or
    -- attributed, so it is refused at the schema rather than discovered during
    -- an evaluation.
    CONSTRAINT label_human_is_attributable CHECK (
        source <> 'HUMAN_REVIEW' OR (reviewer IS NOT NULL AND stratum IS NOT NULL)),

    -- Nothing can be observed before the thing it describes.
    CONSTRAINT label_observed_after_subject CHECK (observed_at >= labelled_as_of)
);

CREATE INDEX idx_label_account ON account_labels (account_id);
CREATE INDEX idx_label_source  ON account_labels (source);

-- Natural idempotency, rather than an Idempotency-Key header.
--
-- Money-moving writes take a key because two payments that look identical may
-- genuinely be two payments. A label does not have that problem: one reviewer's
-- verdict about one account at one instant is one fact, however many times it
-- is submitted. So the data's own identity carries the uniqueness, and a
-- retried submission collides instead of inventing a second opinion that would
-- then show up as a reviewer disagreeing with themselves.
CREATE UNIQUE INDEX idx_label_one_human_verdict
    ON account_labels (account_id, reviewer, labelled_as_of)
    WHERE source = 'HUMAN_REVIEW';

CREATE UNIQUE INDEX idx_label_one_per_dispute
    ON account_labels (account_id, evidence_id)
    WHERE source = 'DISPUTE_FEED';

CREATE UNIQUE INDEX idx_label_one_synthetic
    ON account_labels (account_id, labelled_as_of)
    WHERE source = 'SYNTHETIC';
