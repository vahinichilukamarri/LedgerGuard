# LedgerGuard

A payment integrity platform, built in locked phases.

**Current phase: Phase 13 — Composite Ceiling Fix.** Phase 12 found that the
composite could not flag an account on one signal, however extreme, once three
of the five were measurable. The aggregation is now an unrenormalised weighted
power mean of degree three, and **any single saturated signal elevates any
account** — with the exponent derived from the existing weights rather than
chosen. Re-running Phase 12's benchmark also found two defects in that
benchmark, so one of its published figures was measuring the fixture rather
than the detector. See
[COMPOSITE_CEILING_FIX_REPORT.md](COMPOSITE_CEILING_FIX_REPORT.md),
[VALIDATION_REPORT.md](VALIDATION_REPORT.md),
[LLM_EXPLANATION_REPORT.md](LLM_EXPLANATION_REPORT.md),
[EXPLANATION_REPORT.md](EXPLANATION_REPORT.md),
[ML_DETECTION_REPORT.md](ML_DETECTION_REPORT.md) and
[DETECTION_REPORT.md](DETECTION_REPORT.md).

| Phase | Tag | What it added |
|---|---|---|
| 1 — Ledger Core | `v0.1-ledger-core` | Double-entry accounts, payments, transactions and immutable postings, with balances derived from the postings ledger. |
| 2 — Refunds, Reversals & Transaction Safety | `v0.2-transaction-safety` | Full and partial refunds, single-use reversals, and proven all-or-nothing writes. Both are new transactions, never edits. |
| 3 — Idempotency & Safe Retries | `v0.3-idempotency` | Required idempotency keys, byte-identical replay, and exactly-one-effect under concurrent duplicates. |
| 4 — Transactional Outbox + Kafka | `v0.4-kafka-outbox` | Events written with the ledger transaction, published after commit, at-least-once with consumer-side deduplication. |
| 5 — Settlement Simulator & Reconciliation | `v0.5-reconciliation` | An independent external settlement source, six discrepancy classifications, and persisted incidents with evidence linkage. |
| 6 — Verification & Property-Based Testing | `v0.6-verification` | 38 jqwik properties over randomized payments, refunds, reversals, currencies, amounts and replays. No new feature; one defect found and fixed. |
| 7 — ChaosLab: Fault Injection & Resilience | `v0.7-chaoslab` | 15 deterministic fault-injection scenarios at the JDBC, broker and clock seams, plus a harness self-test. No new feature; one documentation defect found and fixed, no functional defect. |
| 8 — Statistical Anomaly Detection | `v0.8-detection-statistical` | Five anomaly signals over robust statistics and exact discrete tails, combined into a weighted composite. Read-only, query-time, no ML. |
| 9 — Isolation Forest ML Detection | `v0.9-detection-ml` | A hand-rolled Isolation Forest over an 11-feature vector, scored in parallel with the statistical composite and never blended with it. Unvalidated: no labelled data exists. |
| 10 — Explanation Layer | `v0.10-explanation` | Signal contributions that sum to the composite, isolation-bit attribution for the forest, disagreement between the layers made legible, and template-generated summaries. No new detection. |
| 11 — LLM-Backed Explanation | `v0.11-explanation-llm` | Narratives written by a Groq-hosted model from a closed evidence payload, validated name-by-name and number-by-number before serving, with the Phase 10 templates as a silent fallback. No new detection, no change to any score. |
| 12 — Validation | `v0.12-validation` | Labels from an independent dispute feed and a stratified blind review queue, plus a leakage-safe evaluation harness. Measures the detector without tuning it, and found a structural ceiling in the Phase 8 composite. |
| 13 — Composite Ceiling Fix | `v0.13-ceiling-fix` | The aggregation replaced with an unrenormalised weighted power mean of degree three, closing the ceiling and removing the thin-vs-measured inversion. No weight or threshold tuned, no signal or model changed. |

Nothing beyond those thirteen phases is implemented.

---

## The invariant

> **For every transaction, and for every currency within it, the sum of debits
> must equal the sum of credits.**

A transaction that does not satisfy this is never persisted — not partially, not
at all.

### Where it is enforced

| Layer | What it does |
|---|---|
| **`TransactionService.requireBalanced`** (`transactions/TransactionService.java`) | **The enforcement point.** Nets every posting by currency and throws `UnbalancedTransactionException` if any currency is non-zero. |
| `TransactionService.createBalanced` | The only write path into `transactions` and `postings`. Runs the check *before* issuing a single INSERT, inside the same `@Transactional` boundary that would do the writing — so there is no window where a half-written unbalanced transaction is visible. |
| `PaymentService.create` | Constructs the balanced pair (CREDIT source, DEBIT destination) and calls `createBalanced`. The payment row and the postings commit or roll back together. |
| `ApiExceptionHandler` | Surfaces a violation as HTTP **422 Unprocessable Entity** — the request was well-formed, but committing it would have broken the ledger. |

The check is deliberately free of persistence concerns so it can be reasoned
about and unit-tested on its own. See `TransactionServiceBalanceTest`.

Two details worth knowing:

- **The check is per currency, not across currencies.** A 1000 USD debit against
  a 1000 EUR credit sums to zero only if you conflate currencies, so it is
  rejected.
- **Sums use `Math.addExact`.** An overflow becomes a loud failure rather than a
  wrapped-around total that happens to net to zero.

### Sign convention

A **DEBIT increases** an account balance; a **CREDIT decreases** it.

```
balance = sum(DEBIT amounts) - sum(CREDIT amounts)
```

So a payment **CREDITs the source** (money leaves) and **DEBITs the destination**
(money arrives). Posting amounts are always strictly positive — direction lives
in the posting `type`, never in the sign of the amount.

### Money representation

Money is stored **exclusively as integer minor units** in `BIGINT` columns.
`$10.25` is `1025`. There is no `NUMERIC`, `DECIMAL`, `FLOAT` or `DOUBLE` money
column anywhere in the schema.

`BigDecimal` appears in exactly one place: the API boundary, via
`config/Money.java`. `PaymentController` converts an inbound decimal to minor
units on arrival; the response DTOs render minor units back to a decimal on the
way out. An amount finer than the currency's minor unit (`10.255 USD`) is
**rejected with 400, never rounded** — silently dropping a fraction of a cent is
how ledgers stop balancing.

### Immutability of postings

Postings are append-only, enforced three ways deliberately redundantly:

1. `Posting` has **no setters** and no mutating methods.
2. Every column is mapped **`updatable = false`**, so even a reflective field
   change on a managed entity produces no `UPDATE` at flush time.
3. `PostingRepository` extends bare `Repository`, **not `CrudRepository` or
   `JpaRepository`** — so `save()` and `delete()` are not on the interface at
   all. Inserts happen in one place only: `TransactionService`, via the
   `EntityManager`, after the balance check passes.

`PostingImmutabilityTest` fails the build the moment any of those three erode.

### Account balances

`GET /accounts/{id}/balance` is **derived**, not stored. It is one indexed
aggregate over `postings` (`idx_postings_account_currency`). There is no cached
balance column, because a derived balance cannot disagree with the postings that
produced it — which is the entire point of keeping a ledger.

---

## Refunds and reversals (Phase 2)

### Neither one edits anything

Both are **corrections written forwards**. Nothing in either flow modifies an
existing row — they add new transactions whose postings move money the other
way. This is not a policy either service chooses to follow: postings are
physically unmodifiable (no setters, `updatable = false`, no repository write
path), so a refund *could not* edit the original even if it tried.

The practical consequence is that the ledger keeps the whole story. After a
refund you can still see what was originally paid, when, and what was given
back. An implementation that edited the original amount down would silently
destroy that.

### How they differ

| | Refund | Reversal |
|---|---|---|
| **Scope** | A payment | Any transaction |
| **Amount** | Full or partial | Always the whole thing |
| **How many** | Many per payment, up to the amount paid | Exactly one, ever |
| **Endpoint** | `POST /payments/{id}/refunds` | `POST /transactions/{id}/reversals` |
| **Builds legs from** | The payment's source and destination | Reading back the original postings, whatever they are |
| **Limit enforced by** | `RefundService` + a row lock | A UNIQUE constraint in the database |

A **refund** is a business event: a customer gets some money back, possibly in
instalments. It knows there is a payer and a payee, so it can refund part of
the amount and do so repeatedly, up to the total paid.

A **reversal** is a correction: this transaction should not have happened.
It makes no assumption about shape — it reads whatever postings the original
has, however many legs and in whatever currencies, and emits the opposite of
each. Because the original balanced, its exact negation balances too. It still
goes through `TransactionService.createBalanced`, so the check runs rather than
being assumed.

### The refund cap, and the race it hides

The rule is `sum(refunds.amount_minor) ≤ payments.amount_minor`. That spans
multiple rows, and a `CHECK` constraint can only see the row being written —
the same reason `Σ debits = Σ credits` lives in `TransactionService` rather
than in the schema.

Being service-layer only creates a genuine hazard: two refunds arriving at once
could both read the same remaining balance, both find it sufficient, and both
commit. `RefundService` therefore loads the payment with
`PaymentRepository.findByIdForUpdate`, taking a `PESSIMISTIC_WRITE` lock on the
row for the rest of the transaction. Refunds against the *same* payment
serialise; refunds against different payments are unaffected.

Reversals need no such lock: `reversals.original_transaction_id` is `UNIQUE`,
so a second concurrent reversal simply fails to insert.

### The cumulative refunded amount is derived

There is no `refunded_total` column on `payments`. The figure is
`SUM(amount_minor)` over the payment's refund rows, computed when asked —
consistent with how account balances work in Phase 1, and for the same reason.

---

## Atomicity

Every write path that spans more than one insert is a single `@Transactional`
unit. Creating a refund writes four rows — a transaction, two postings, and the
refund record — and either all four commit or none do. Reversals are the same,
as payments already were in Phase 1.

### The deliberate-failure test

Claiming all-or-nothing is easy; the suite proves it. `failureMidWriteCommitsNothing`
in `RefundReversalFlowIntegrationTest` records the row counts, then forces the
**last** insert of a refund to throw:

```java
doThrow(new IllegalStateException("simulated failure after the postings were written"))
        .when(refundRepositorySpy).save(any(Refund.class));
```

The ordering is what makes this a real test rather than a formality.
`createBalanced` runs first and **flushes**, so by the time the failure fires,
the transaction row and both postings have genuinely reached PostgreSQL. The
test then reads the database directly with `JdbcTemplate` and asserts the
counts are unchanged and the payer's balance has not moved. If the transactional
boundary were wrong, those flushed rows would still be there.

It finishes by performing the same refund again successfully, showing the
rollback left no broken state behind.

---

## Idempotency (Phase 3)

### The problem

A client sends `POST /payments`. The connection drops before the response gets
back. The client, correctly, retries.

Phases 1 and 2 would create a second payment and move the money twice. Nothing
in the ledger prevents it: both requests are individually valid, both produce
balanced transactions, and the invariant holds perfectly while the customer is
charged twice. **A consistent ledger and a correct one are not the same thing.**

### The design

Every write endpoint requires an `Idempotency-Key` header. The key plus a
fingerprint of the request decides what happens:

| Situation | Result |
|---|---|
| Key never seen | The work runs. Response stored against the key. |
| Key seen, **same** request | The original response is replayed byte for byte, with `Idempotent-Replay: true`. |
| Key seen, **different** request | **409** `idempotency_key_conflict`. Nothing runs. |
| No key at all | **400** `idempotency_key_required`. |

Keys are scoped per endpoint, so the same token on `/payments` and on a refund
are unrelated.

### Four decisions worth explaining

**The header is required, not optional.** An optional guard leaves the failure
mode in place for exactly the clients most likely to retry badly. Being pre-1.0
made the breaking change cheap now and expensive later.

**Conflict is 409, not 422.** Every 422 here means "the ledger refused this" —
unbalanced postings, the refund cap, reverse-once. Key reuse is not a ledger
rule; it is a request conflicting with existing state. Keeping 422 to mean one
thing is worth protecting.

**Replay is signalled in a header.** The guarantee is that the replayed body is
*byte-identical*. A body field would break that by definition. Both paths return
the very same stored string, so identity is structural rather than hoped-for.

**The fingerprint is a hash of method + path + canonical body**, where canonical
means object keys sorted recursively. Key order therefore cannot change it. One
deliberate limitation: `"4.00"` and `"4.0"` fingerprint differently and so
conflict. Treating them as equivalent means guessing at intent, and guessing
wrong means honouring a genuinely different request under a used key.

### How the concurrency guarantee actually works

The idempotency row is inserted **and flushed before the handler runs**. That
ordering is the whole design:

1. Request A inserts the row and flushes. The INSERT is real but uncommitted.
2. Request B, same key, attempts the same INSERT and **blocks** on
   `idempotency_keys_unique`. B has not touched the ledger.
3. A does its ledger writes, records its response on the row, commits.
4. B unblocks, fails with a unique violation, rolls back having written
   nothing, re-reads the committed row and replays A's exact response.

If A rolls back instead, B's INSERT succeeds and B proceeds as the winner.
Either way exactly one set of ledger writes commits: not two, and not zero.

**Trade-off:** B holds its connection for as long as A's transaction takes.
Fine here, where handlers finish in milliseconds. A long-running handler would
want an explicit `IN_PROGRESS` row and an immediate 409 telling the client to
retry shortly, rather than making it wait.

The detection is deliberately narrow — only SQLState `23505` on
`idempotency_keys_unique` counts as a lost race. Treating any integrity
violation as "someone beat me" would mean replaying another request's response
when the real problem was a ledger constraint.

### Atomicity

The key and the ledger writes commit together. A key recorded without its
transaction would poison every future retry of a request that never happened;
a transaction without its key would be silently un-deduplicated.
`failedWriteLeavesNoKeyBehind` forces the handler to fail after the key row has
been inserted and flushed, then asserts no key row survives — and proves it by
successfully reusing that same key for a different request afterwards.

### Retention

`expires_at` is written and never read. Nothing sweeps expired keys yet. The
column exists now because adding a retention column to an already-populated
table later means backfilling every row; the sweeper belongs to a later phase.

---

## Evidence: 100 concurrent duplicate requests

```
Requests:            100
Financial effects:   1     (idempotency_keys row count for that key = 1)
Duplicates handled:  99
Ledger drift:        $0    (verified via SUM across all postings)
```

**How these were captured.** By firing 100 concurrent `curl` processes from a
shell against a running instance and then querying PostgreSQL directly — not
from a unit test assertion. The integration suite proves the same property
against a Testcontainers database, but the figures above come from real HTTP
traffic against a real server, checked by reading the rows afterwards.

Measured on 2026-09-10, not estimated. Both runs below fired 100 `curl`
processes released together at `POST /payments` for $12.34.

### Run 1 — 100 requests, one shared `Idempotency-Key`

| Measurement | Result |
|---|---|
| HTTP responses | 100, all `201` |
| **Distinct `paymentId`s returned** | **1** |
| Responses carrying `Idempotent-Replay: true` | 99 |
| `payments` rows created | 1 |
| `idempotency_keys` rows created | 1 |
| Money actually moved | **$12.34, once** |
| Ledger drift | **$0.00** |

99 of the 100 duplicates were prevented. One request did the work; the rest
received its exact response.

### Run 2 — 100 requests, 100 distinct keys, identical body

| Measurement | Result |
|---|---|
| HTTP responses | 100, all `201` |
| **Distinct `paymentId`s returned** | **100** |
| Responses carrying `Idempotent-Replay: true` | 0 |
| `payments` rows created | 100 |
| Money actually moved | **$1,234.00** |
| Ledger drift | **$0.00** |

**What run 2 is, precisely.** It is not the Phase 2 code re-run — that code was
not reintroduced, and no figure here is extrapolated from it. It is the current
code with deduplication made inapplicable, because every request carries a
different key. It measures what 100 un-deduplicated identical requests do, which
is the shape of the bug Phase 3 exists to prevent. Read it as an upper bound on
the damage, not as a replay of history.

### The uncomfortable part

**Ledger drift was $0.00 in both runs.** The Phase 1 invariant held perfectly
while the customer was charged a hundred times. Every one of those 100 payments
was internally balanced.

That is the point worth keeping: `Σ debits = Σ credits` proves the ledger is
*self-consistent*. It says nothing about whether the money should have moved at
all. Duplicate suppression is a different guarantee, and it needed its own
mechanism.

---

## The transactional outbox (Phase 4)

### The problem, stated plainly

Nothing published events before this phase. The moment you add a naive
`kafkaTemplate.send()` next to `paymentService.create()`, you get one of two
bugs, and there is no ordering that avoids both:

| Where you put the send | What breaks |
|---|---|
| **Inside** the transaction | Kafka accepts the event, the database then rolls back. An event announces a payment that does not exist. |
| **After** the commit | The database commits, the process dies before the send. The payment exists and nobody downstream ever hears about it. |

Postgres and Kafka cannot commit together. The outbox sidesteps the choice
rather than solving it: **the event is written to Postgres in the same
transaction as the ledger rows**, so it is exactly as durable as the payment
itself. A separate poller moves it to Kafka afterwards.

### Writing the event

`OutboxRecorder.record` is annotated `@Transactional(propagation = MANDATORY)`,
and that annotation is the whole pattern in one line. It makes recording an
event outside a transaction an error rather than a subtle production bug: there
is no code path that can write an outbox row which is not bound to the ledger
rows it describes. Either both commit, or neither does.

`PaymentService`, `RefundService` and `ReversalService` each call it as the last
step of their existing `@Transactional` method. `TransactionService` was not
touched — the ledger invariant code does not need to know events exist.

### Publishing the event

`OutboxPublisher` polls every second:

```
claim unpublished rows  ->  send to Kafka  ->  mark published_at
```

Rows are claimed with `SELECT ... FOR UPDATE SKIP LOCKED`, so two publisher
instances never grab the same row and neither blocks the other.

On a send failure the batch **stops** rather than aborting: events the broker
already confirmed keep their `published_at`, and the failed one is retried next
cycle. Rolling the whole batch back would republish events Kafka has already
accepted, manufacturing the duplicates this design tries to keep rare.

### Delivery is at-least-once. It is not exactly-once.

This is worth being blunt about. If the publisher dies between the send and the
mark, the row is still unpublished and gets sent again on the next poll.
**That duplicate is unavoidable.** Marking before sending would trade it for a
lost event, which is strictly worse for a payments system: a consumer can
discard a duplicate, but nobody can recover a loss.

So consumers must deduplicate. `LedgerEventConsumer` claims each event by
inserting `(consumer_name, event_id)` into `processed_events` before doing any
work, and lets the composite primary key settle races. A key violation means
someone already handled it and the delivery becomes a no-op.

Two details that make this actually work:

- **`eventId` is the outbox row id**, so a republished event carries the *same*
  dedupe key as its first delivery. An id generated at send time would make
  every retry look like a new event.
- **The claim happens before the work, not after.** Claiming afterwards leaves a
  window where a crash loses the record of work that was actually done, turning
  at-least-once *delivery* into at-least-once *effects*.

### Topics and payload

```
ledgerguard.payments.v1     PaymentPosted
ledgerguard.refunds.v1      PaymentRefunded
ledgerguard.reversals.v1    TransactionReversed
```

Named `<system>.<aggregate>.<version>`. Separate topics so a consumer that only
cares about refunds is not made to filter everything else, and the `v1` is in
the name so a breaking payload change becomes a new topic rather than a silent
deserialization failure.

Every message uses the same envelope:

```json
{
  "eventId":       "uuid",
  "eventType":     "PaymentPosted",
  "aggregateType": "Payment",
  "aggregateId":   "uuid",
  "occurredAt":    "2026-09-10T12:18:09Z",
  "payload":       { "paymentId": "...", "amountMinor": 1234, "currency": "USD" }
}
```

The payload carries enough for a consumer to act **without calling back into
the API** — a refund event includes the running refunded total and what remains
refundable, so nothing has to be looked up.

**Amounts stay integer minor units on the wire.** Serialising `12.34` would
reintroduce the precision problem Phase 1 exists to prevent, at the system
boundary where it is hardest to notice. `occurredAt` is pinned to a string with
`@JsonFormat`, because with default Jackson settings an `Instant` serialises as
a float epoch and a published wire contract should not depend on a framework
default somebody could change.

`aggregateId` is the Kafka message key, so every event about one payment lands
in one partition and stays ordered.

---

## Failure demo: Kafka goes down, nothing is lost

Run this yourself. It is the point of the whole phase.

**Prerequisites:** `docker compose up -d` and `mvn spring-boot:run`, both
healthy. Commands are Git Bash.

### 1. Create two accounts and stop Kafka

```bash
ALICE=$(curl -s -X POST http://localhost:8080/accounts -H "Content-Type: application/json" -d '{"name":"Alice","currency":"USD"}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'); BOB=$(curl -s -X POST http://localhost:8080/accounts -H "Content-Type: application/json" -d '{"name":"Bob","currency":"USD"}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'); echo "ALICE=$ALICE BOB=$BOB"
```

```bash
docker compose stop kafka
```

### 2. Submit a payment with the broker down

```bash
RESP=$(curl -s -w "\nHTTP %{http_code}" -X POST http://localhost:8080/payments -H "Content-Type: application/json" -H "Idempotency-Key: kafka-demo-$(date +%s)" -d "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":\"12.34\",\"currency\":\"USD\",\"description\":\"kafka down\"}"); echo "$RESP" | tail -1; PID=$(echo "$RESP" | sed -n 's/.*"paymentId":"\([^"]*\)".*/\1/p'); echo "PAYMENT=$PID"
```

**It returns `HTTP 201`.** The request path never touches Kafka.

### 3. Confirm the ledger committed anyway

```bash
docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT (SELECT COUNT(*) FROM payments WHERE id='$PID') AS payment, (SELECT COUNT(*) FROM postings WHERE transaction_id=(SELECT transaction_id FROM payments WHERE id='$PID')) AS postings;"
```

Prints `1|2` — the payment and both postings are committed and durable.

### 4. Confirm the event is sitting unpublished

```bash
docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT event_type, COALESCE(published_at::text,'NOT PUBLISHED'), publish_attempts FROM outbox_events WHERE aggregate_id='$PID';"
```

`NOT PUBLISHED`, with `publish_attempts` climbing as the poller retries against
a broker that is not there. Nothing is lost and nothing is stuck.

### 5. Restart Kafka

```bash
docker compose start kafka
```

### 6. Watch it publish itself, with no intervention

```bash
for i in $(seq 1 30); do docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT COALESCE(published_at::text,'NOT PUBLISHED') FROM outbox_events WHERE aggregate_id='$PID';"; sleep 2; done
```

Within a few seconds it flips from `NOT PUBLISHED` to a timestamp. Nothing was
retried by hand.

### 7. Confirm the consumer processed it exactly once

```bash
docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT COUNT(*) FROM processed_events WHERE event_id=(SELECT id FROM outbox_events WHERE aggregate_id='$PID');"
```

Prints `1`. And nothing is left behind:

```bash
docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL;"
```

### Measured run

Executed on 2026-09-10 against a running instance:

```
Kafka stopped:              payment returned HTTP 201
Ledger after the request:   1 payment, 2 postings, balance -1234
Outbox state:               published_at NULL, publish_attempts 3 and climbing
Kafka restarted:            published automatically after ~3s (attempts 5)
Consumer:                   processed_events rows for that event = 1
Unpublished events left:    0
```

### A Git Bash wrinkle

Git Bash rewrites arguments that look like absolute paths, so
`docker exec ledgerguard-kafka /opt/kafka/bin/kafka-topics.sh ...` becomes
`C:/Program Files/Git/opt/kafka/...` and fails. Prefix those with
`MSYS_NO_PATHCONV=1`:

```bash
MSYS_NO_PATHCONV=1 docker exec ledgerguard-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

The `psql` commands above are unaffected because they pass no absolute paths.

---

## Reconciliation (Phase 5)

### What this catches that the ledger invariant cannot

Phases 1 to 4 prove the ledger agrees **with itself**. Debits equal credits, no
transaction is half-written, no retry double-charges, no event is lost. None of
that says the ledger agrees with **anyone else**.

Every one of these is internally perfect and externally wrong:

- We recorded a $250 payment; the processor settled $200.
- We recorded a payment; the processor has no record of it.
- The processor settled the same payment twice.
- Money moved at the processor that our ledger has never heard of.

Phase 3 already showed this shape: 100 duplicate payments, every one balanced,
ledger drift $0.00, and the customer charged a hundred times. **Internal
consistency is necessary and nowhere near sufficient.** Reconciliation is how
the second kind of wrongness gets found.

### The six outcomes

Every comparison lands in exactly one.

| Outcome | One-line example | Base severity |
|---|---|---|
| `MATCHED` | We say $250, they say $250, both settled. | no incident |
| `MISSING_SETTLEMENT` | We posted $250; the settlement file has nothing. | MEDIUM |
| `AMOUNT_MISMATCH` | We recorded **$250**, they settled **$200**. | MEDIUM |
| `DUPLICATE_SETTLEMENT` | One payment, two settlement records. | HIGH |
| `STATUS_MISMATCH` | Both say $250; we say POSTED, they say FAILED. | LOW |
| `UNEXPECTED_EXTERNAL_TRANSACTION` | They settled $99 against a reference we do not have. | HIGH |

When both the amount **and** the status differ, it is classified
`AMOUNT_MISMATCH`. Exactly one classification is allowed and the money is the
more actionable fact; the status is still stored on the incident as evidence.
A differing **currency** is also an `AMOUNT_MISMATCH` — the amounts are not
comparable, so they do not agree.

### The matching key

An external record is paired to an internal transaction by
**`external_reference == transaction_id`** — the processor's echo of our own
reference, which is how real settlement files correlate.

Ambiguity in that key is not an edge case to defend against; it *is* three of
the six outcomes:

| Key situation | Outcome |
|---|---|
| exactly one external record | compare amount, currency, status |
| no external record | `MISSING_SETTLEMENT` |
| two or more with the same reference | `DUPLICATE_SETTLEMENT` |
| reference is blank, or names a transaction we do not have | `UNEXPECTED_EXTERNAL_TRANSACTION` |

A blank reference is deliberately treated as unmatched rather than skipped.
Dropping it would mean silently ignoring a real movement of money.

**The grace window.** The simulator consumes events asynchronously, so a
transaction committed a second ago legitimately has no settlement record yet.
Reporting that as `MISSING_SETTLEMENT` would be noise. Transactions younger than
`ledgerguard.reconciliation.grace-seconds` (default 5) are counted as *awaiting
settlement* — neither matched nor a discrepancy. Real reconciliation runs T+1
for the same reason; this is that idea compressed to seconds.

### Severity: type sets the floor, amount escalates

| | |
|---|---|
| Base | from the discrepancy type, per the table above |
| ≥ **$100** in question | escalate to at least `HIGH` |
| ≥ **$1,000** in question | escalate to `CRITICAL` |
| Escalation | only ever raises, never lowers |

The reasoning: **type** tells you how uncontrolled the money is, **amount**
tells you the exposure. A one-cent mismatch and a ten-thousand-dollar mismatch
are the same bug shape but not the same alert. Conversely a duplicate
settlement of one cent stays `HIGH`, because the number is not the point —
a control failed and nothing stopped it.

What counts as "in question" differs sensibly by type: the whole amount for a
missing settlement, the difference for an amount mismatch, and only the **extra
copies** for a duplicate, since one of them was supposed to happen.

**A zero-difference status mismatch is still flagged**, at `LOW`. Agreeing
amounts are exactly what makes it easy to overlook, and "we think it settled,
they think it failed" is usually the precursor to a real loss rather than a
harmless annotation.

### One standing problem is one incident

A discrepancy nobody has resolved is still there on the next run. Filing it
again each time would turn one standing problem into a stream of alerts, so a
run **skips any discrepancy that already has an OPEN incident** for the same
type and the same pair of records. The run still reports it: `discrepancies` is
how many were found, `newIncidents` how many were filed, and `alreadyOpen` the
difference.

Resolving an incident releases the key, so if the disagreement is still there on
a later run it is raised again — which is right, because nobody is looking at it
any more.

### MATCHED creates no incident

Incidents are exceptions that need action. A row per agreement would bury the
handful that matter. Agreement is recorded as a **count** on the
`reconciliation_runs` row instead, so a run can report that it examined 400
transactions and agreed on 397 without turning the incident table into a log.

### Why the simulator consumes Kafka instead of reading the ledger

This is the decision the whole phase rests on. If `SettlementSimulator` queried
`transactions` and copied the amounts, reconciliation would compare the ledger
against a mirror of itself and pass by construction. **A comparison that cannot
fail is not a comparison.**

So it consumes the published event stream and nothing else — exactly what a real
processor receives: an instruction, arriving asynchronously, applied under its
own rules and stored in its own table. It runs in its own consumer group and has
no access to `transactions` or `postings`.

Two consequences worth noting:

- **`settlement_records` is mutable.** Every other money-bearing table here is
  append-only because we own it. We do not own the processor: it restates
  amounts, moves statuses and withdraws records. Modelling that faithfully is
  what makes reconciliation worth running, and it is why the fault endpoint
  edits those rows rather than the ledger.
- **The simulator deduplicates on event id.** Delivery is at-least-once, and a
  settlement record created by a *redelivery* would surface as a
  `DUPLICATE_SETTLEMENT` incident describing a bug in the simulator rather than
  anything about the ledger.

### On demand, not scheduled

Reconciliation runs when you call `POST /reconciliation/runs`. Three reasons:
you almost always want to run it and read the result in the same breath; a
scheduled version makes tests wait on wall-clock timing and hides *when* a run
happened; and real reconciliation is a batch job, which is closer to "triggered"
than to "continuous". `@EnableScheduling` is already on from Phase 4, so a
nightly trigger is one annotation away — deliberately not taken.

---

## Demo: produce each discrepancy yourself

Every command is Git Bash. Start with `docker compose up -d` and
`mvn spring-boot:run`, both healthy.

### Helpers

Paste these once; the rest of the section uses them.

```bash
API=http://localhost:8080
mk() { curl -s -X POST $API/accounts -H "Content-Type: application/json" -d "{\"name\":\"$1\",\"currency\":\"USD\"}" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'; }
pay() { A=$(mk "$1 Payer"); B=$(mk "$1 Payee"); curl -s -X POST $API/payments -H "Content-Type: application/json" -H "Idempotency-Key: demo-$1-$(date +%s%N)" -d "{\"sourceAccountId\":\"$A\",\"destinationAccountId\":\"$B\",\"amount\":\"$2\",\"currency\":\"USD\",\"description\":\"$1\"}" | sed -n 's/.*"transaction":{"id":"\([^"]*\)".*/\1/p'; }
fault() { curl -s -X POST $API/admin/settlement/faults -H "Content-Type: application/json" -d "$1"; echo; }
recon() { curl -s -X POST $API/reconciliation/runs | sed -n 's/.*"matched":\([0-9]*\),"awaitingSettlement":\([0-9]*\),"discrepancies":\([0-9]*\),"newIncidents":\([0-9]*\),"alreadyOpen":\([0-9]*\).*/  matched=\1 awaiting=\2 found=\3 new=\4 alreadyOpen=\5/p'; }
show() { curl -s "$API/reconciliation/incidents?transactionId=$1"; echo; }
```

**Two waits matter here, and getting either wrong makes a demo look broken.**

1. The simulator settles asynchronously, so a fault injected too early fails
   with a 400 telling you so.
2. Reconciliation ignores transactions younger than the **5 second grace
   window** (`ledgerguard.reconciliation.grace-seconds`). Run it too soon and a
   genuine `MISSING_SETTLEMENT` is reported as *awaiting settlement* instead —
   correctly, but not what you were trying to see.

`sleep 7` below clears both. The `recon` helper prints the run summary, so if a
discrepancy you expected does not appear, `awaiting=` tells you immediately that
you simply ran too early rather than that anything is wrong.

To check the external side has caught up:

```bash
curl -s "$API/admin/settlement/records?transactionId=$TXN"
```

### 1. MATCHED — no incident

```bash
TXN=$(pay Matched 10.25); echo $TXN; sleep 7; recon; show $TXN
```

Returns `[]`. Agreement shows up in the run's `matched` count, not as a row.

### 2. MISSING_SETTLEMENT — the processor loses it

```bash
TXN=$(pay Missing 10.25); sleep 7; fault "{\"type\":\"DROP_SETTLEMENT\",\"transactionId\":\"$TXN\"}"; recon; show $TXN
```

`MISSING_SETTLEMENT`, `MEDIUM`, difference `1025`, `settlementRecordId: null` —
there is no external side to point at.

### 3. AMOUNT_MISMATCH — $250 recorded, $200 settled

```bash
TXN=$(pay Amount 250.00); sleep 7; fault "{\"type\":\"RESTATE_AMOUNT\",\"transactionId\":\"$TXN\",\"amountDeltaMinor\":-5000}"; recon; show $TXN
```

`AMOUNT_MISMATCH`, `MEDIUM`, internal `25000`, external `20000`, difference
`5000`. Push the delta past `-10000` and the same fault comes back `HIGH`.

### 4. DUPLICATE_SETTLEMENT — settled twice

```bash
TXN=$(pay Dup 10.25); sleep 7; fault "{\"type\":\"DUPLICATE_SETTLEMENT\",\"transactionId\":\"$TXN\"}"; recon; show $TXN
```

`DUPLICATE_SETTLEMENT`, `HIGH` despite being only $10.25, difference `1025` —
the extra copy, not the whole amount.

### 5. STATUS_MISMATCH — amounts agree, states do not

```bash
TXN=$(pay Status 10.25); sleep 7; fault "{\"type\":\"CHANGE_STATUS\",\"transactionId\":\"$TXN\",\"newStatus\":\"FAILED\"}"; recon; show $TXN
```

`STATUS_MISMATCH`, `LOW`, difference **`0`**, `POSTED` vs `FAILED`. Flagged
precisely because nothing about the numbers looks wrong.

### 6. UNEXPECTED_EXTERNAL_TRANSACTION — money we never authorised

Needs no payment; it references nothing by definition.

```bash
fault '{"type":"PHANTOM_SETTLEMENT","amountMinor":9900,"currency":"USD"}'; recon; curl -s "$API/reconciliation/incidents?type=UNEXPECTED_EXTERNAL_TRANSACTION"; echo
```

`UNEXPECTED_EXTERNAL_TRANSACTION`, `HIGH`, external `9900`,
`transactionId: null` — no internal side exists.

### Triage and resolve

```bash
curl -s "$API/reconciliation/incidents?severity=HIGH&status=OPEN"; echo
```

```bash
curl -s -X POST $API/reconciliation/incidents/<INCIDENT_ID>/resolve; echo
```

Resolved incidents are kept, not deleted: what went wrong is itself a record.

### Measured run

All six executed against a running instance on 2026-09-10, each after a
`sleep 7` so the grace window had passed:

```
MATCHED                          no incident created
MISSING_SETTLEMENT               MEDIUM   difference 1025   external side null
AMOUNT_MISMATCH                  MEDIUM   difference 5000   25000 vs 20000
DUPLICATE_SETTLEMENT             HIGH     difference 1025   two external records
STATUS_MISMATCH                  LOW      difference 0      POSTED vs FAILED
UNEXPECTED_EXTERNAL_TRANSACTION  HIGH     external 9900     internal side null
```

---

## Verification (Phase 6)

Phase 6 adds no feature. It adds a second way of asking whether Phases 1-5 are
correct.

### What property-based testing is

An example-based test names an input and asserts an output: refund $4.00 of a
$10.25 payment, expect `625` to remain. A property-based test names a *rule* and
lets a generator invent the inputs — hundreds of them per run, chosen to include
the awkward ones — then asserts the rule held for every single one.

The two find different bugs, and the difference is not about volume. An
example-based test can only fail in a way its author already imagined; that is
what makes it a good specification and a poor search. A property test is the
search. It has no opinion about which cases matter, which is exactly why it
reaches the ones nobody thought to write down.

**This does not replace the Phase 1-5 tests, and they were not touched.** They
remain the readable specification of what the system does — a named scenario with
concrete numbers is how you explain a refund cap to another person, and no
property statement communicates that. The properties are a net cast around them.
Where the two overlap, the example test is the one that says what is *supposed*
to happen; the property only says that whatever happens is consistent.

When a property fails, jqwik **shrinks** the counterexample: it repeatedly
simplifies the failing input while the failure persists, so what you are handed
is the smallest case that still breaks. That is what turned the defect below from
"something went wrong at 9,683,895 minor units after five operations" into "pay
1, refund 1, reverse" — and the second form is a bug report, while the first is
only a symptom.

### The generators

All in `properties/LedgerArbitraries.java`, deliberately in one class: a property
is only as good as the inputs it sees, so what those inputs are should be
readable in one place.

| Generator | Produces | Edge cases forced on purpose |
|---|---|---|
| `supportedCurrencies` | `USD EUR GBP CHF` (2dp), `JPY KRW VND CLP` (**0dp**), `KWD BHD JOD TND OMR` (**3dp**) | weighted so the unusual minor units are not rare; a 2dp-only generator tests one third of the arithmetic |
| `unusableCurrencies` | malformed (`US`, `USDD`, `1AB`, empty, `u$d`), well-formed but unknown (`ZZZ`, `QQQ`), known with **no minor unit** (`XAU`, `XPT`, `XDR`, `XXX`) | all three distinct failure modes, which are three different paths through `Money` |
| `mixedCaseCurrencies` | every letter-case permutation of a supported code | `usd`, `UsD`, `USD` must be one currency |
| `chainAmountsMinor` | 1 to 10^12 minor units | `1`, `2`, `3`, `99`, `100`, `101`, `999`, `1000`, `Integer.MAX_VALUE` — a single minor unit would essentially never appear in a uniform draw over twelve orders of magnitude, and it is where off-by-one lives |
| `boundaryAmountsMinor` | `Integer.MAX_VALUE`, `Long.MAX_VALUE/2`, `Long.MAX_VALUE-1`, `Long.MAX_VALUE` | the actual top of the representation |
| `subMinorAmounts` | a decimal with exactly one more fraction digit than its currency has, final digit non-zero | 10.255 USD, 10.5 JPY, 1.2345 KWD — must be **rejected, never rounded** |
| `refundPlans` | a payment amount plus partial-refund splits summing to at most it, normalised so the **last partial lands exactly on the remainder** | payments of 2, 3 and 5 minor units, split — the sharpest possible test of the cap |
| `refundPercents` | 1-130% of the payment | over 100% is included so the generator produces refunds that must fail |
| `operationChains` | sequences of 1-6 steps over `REFUND`, `REVERSE_PAYMENT`, `REVERSE_LAST_REFUND`, `REVERSE_LAST_REVERSAL`, `REPLAY_LAST` | invalid sequences are generated deliberately — double reversal, refund past the cap, replay of a refused step. `REVERSE_LAST_REVERSAL` targets the transaction the previous reversal *produced*, which is what lets a chain go past depth two |
| `currencyLegs` | posting sets across 1-3 currencies, each currency independently balanced or skewed by a generated delta | skews of plus/minus 1 and plus/minus 1000, so one generator produces sets that must be accepted **and** sets that must be rejected |

Amounts are generated as **minor units** and converted to decimals with
`Money.toMajorUnits`, so a generated case is always exactly representable in its
currency — unless being unrepresentable is the point of that generator.

### The properties

38 properties across eight classes. **In-memory** where the property is about a
pure function; **Testcontainers PostgreSQL** wherever it depends on persistence,
a schema constraint or a row lock actually holding, rather than on in-memory
logic agreeing with itself.

| Property | Class | Generators | Runs against | Tries |
|---|---|---|---|---|
| Postings are accepted **if and only if** every currency nets to zero | `BalanceInvariantPropertyTest` | `currencyLegs` | in-memory | 1000 |
| Verdict is independent of posting order | `BalanceInvariantPropertyTest` | `currencyLegs` + shuffle seed | in-memory | 1000 |
| Currencies never net against each other (two currencies skewed to cancel exactly) | `BalanceInvariantPropertyTest` | two distinct currencies, amount, skew | in-memory | 1000 |
| Adding balanced pairs never turns a good transaction bad | `BalanceInvariantPropertyTest` | currency, amount, pair count | in-memory | 1000 |
| A single posting is never a transaction | `BalanceInvariantPropertyTest` | currency, amount | in-memory | 1000 |
| Overflowing sums fail loudly instead of wrapping to a false zero | `BalanceInvariantPropertyTest` | amounts above `Long.MAX_VALUE/2` | in-memory | 1000 |
| Minor units round-trip exactly through decimal | `MoneyPropertyTest` | `supportedCurrencies`, `chainAmountsMinor` | in-memory | 1000 |
| ...and still do at the boundaries of the representation | `MoneyPropertyTest` | `boundaryAmountsMinor` | in-memory | 1000 |
| Decimal scale always matches the currency | `MoneyPropertyTest` | `supportedCurrencies` | in-memory | 1000 |
| Sub-minor precision is always rejected, never rounded | `MoneyPropertyTest` | `subMinorAmounts` | in-memory | 1000 |
| Unusable currency codes are rejected at every entry point | `MoneyPropertyTest` | `unusableCurrencies` | in-memory | 1000 |
| Currency case is normalised, not honoured | `MoneyPropertyTest` | `mixedCaseCurrencies` | in-memory | 1000 |
| Non-positive amounts are never postings | `MoneyPropertyTest` | `supportedCurrencies`, amounts | in-memory | 1000 |
| Amounts past the representation are rejected, not truncated | `MoneyPropertyTest` | `boundaryAmountsMinor` | in-memory | 1000 |
| **Every persisted transaction nets to zero per currency** | `LedgerPersistencePropertyTest` | currency, amount | **Postgres** | 120 |
| The persisted amount is the requested amount in minor units | `LedgerPersistencePropertyTest` | currency, amount | **Postgres** | 120 |
| **Postings in different currencies never net against each other** | `LedgerPersistencePropertyTest` | two currencies, two amounts | **Postgres** | 120 |
| A payment in the wrong currency is refused and writes nothing | `LedgerPersistencePropertyTest` | two currencies, amount | **Postgres** | 120 |
| Every refused payment writes nothing at all | `LedgerPersistencePropertyTest` | currency, amount, rejection kind | **Postgres** | 120 |
| **One key means exactly one financial effect**, sequential replays | `IdempotencyPropertyTest` | currency, amount, replay count, inter-replay gap | **Postgres** | 100 |
| **...and the same under a genuine race**, N threads released together | `IdempotencyPropertyTest` | currency, amount, thread count 2-8 | **Postgres** | 25 |
| A replayed refund has exactly one financial effect | `IdempotencyPropertyTest` | currency, amount, replay count | **Postgres** | 100 |
| A replayed reversal has exactly one financial effect | `IdempotencyPropertyTest` | currency, amount, replay count | **Postgres** | 100 |
| **Refunds never exceed the refundable amount, across any sequence of partials** | `RefundCapPropertyTest` | `refundPlans` | **Postgres** | 100 |
| One minor unit past the cap is always refused — and does not consume the cap | `RefundCapPropertyTest` | `refundPlans` | **Postgres** | 100 |
| A refund is accepted **iff** it fits in what remains, in any order | `RefundCapPropertyTest` | currency, amount, `refundPercents` | **Postgres** | 100 |
| **A transaction and its reversal net to zero together** | `ReversalPropertyTest` | currency, amount | **Postgres** | 80 |
| **A transaction can only ever be reversed once** | `ReversalPropertyTest` | currency, amount, attempt count | **Postgres** | 80 |
| Reversing a refund undoes exactly the refund | `ReversalPropertyTest` | currency, amount | **Postgres** | 80 |
| Reversing a reversal reinstates the original exactly | `ReversalPropertyTest` | currency, amount | **Postgres** | 80 |
| **Reversal chains conserve money at any depth**, and alternate exactly | `ReversalPropertyTest` | currency, amount, depth 1-6 | **Postgres** | 80 |
| **No chain of operations ever creates money** | `TransactionSequencePropertyTest` | `operationChains`, `refundPercents` | **Postgres** | 80 |
| No chain of operations ever unbalances the ledger | `TransactionSequencePropertyTest` | `operationChains` | **Postgres** | 80 |
| Refunds across any chain never exceed the payment | `TransactionSequencePropertyTest` | `operationChains` | **Postgres** | 80 |
| **Redelivering one event handles it exactly once** | `EventReplayPropertyTest` | event type, delivery count 1-8 | **Postgres** | 100 |
| Interleaved redeliveries never suppress a genuine event | `EventReplayPropertyTest` | event count, copies, shuffle seed | **Postgres** | 100 |
| **Replaying a processed event never changes financial state** | `EventReplayPropertyTest` | event type, delivery count | **Postgres** | 100 |
| Unparseable messages are skipped without claiming anything | `EventReplayPropertyTest` | malformed strings | **Postgres** | 100 |

#### Why those tries counts

jqwik's default is 1000. The in-memory properties run at that default, unchanged:
they touch nothing but `BigDecimal` and `java.util.Currency`, so tries are free
and there is no reason to run fewer — all fourteen of them finish in well under a
second together.

The database-backed properties are lowered to **80-120**, and the concurrent one
to **25**. One try there creates accounts and performs one to seven full write
cycles against a real PostgreSQL — five to twenty round trips, some taking a row
lock. At 1000 tries a single property would run for tens of minutes and the suite
would stop being something anyone runs before pushing.

The compensation is in the generators rather than the count: the boundary values
are **forced as edge cases** instead of being waited for. A payment of one minor
unit, a zero-decimal currency, a refund of exactly the remainder — none of those
would show up reliably in 1000 uniform draws either, and all of them appear in
every run here. The defect below was found at 80 tries and shrank to a
three-operation reproducer.

The concurrent property is 25 tries because each spawns up to eight threads that
genuinely block on the PostgreSQL unique index while the winner finishes its
ledger writes. Across the property that is still several hundred real races.

### Testcontainers

Container configuration lives in one place, `support/LedgerPostgres`, and is used
two ways:

- `newContainer()` — a fresh container per JUnit Jupiter class, which is what
  every Phase 1-5 integration test uses. Those keep their own database, and that
  isolation is load-bearing: `ReconciliationFlowIntegrationTest` reconciles the
  **entire** ledger, so rows written by another class would surface in its
  results as discrepancies.
- `shared()` — one container for the whole property suite, started once. Every
  property either scopes its assertions to accounts created in that try, or
  asserts something true of any ledger (the whole-ledger sum is zero no matter
  who else wrote to it), so sharing is safe there and saves starting a container
  per property class.

`support/PropertyLedger` boots the application once against that shared container
and holds it for the life of the JVM. It has to: Spring's `SpringExtension` is a
JUnit **Jupiter** extension and jqwik is a separate JUnit Platform engine, so a
`@Property` method never passes through Jupiter's lifecycle and never gets a
context injected. Rather than add a bridge library, the context is built directly.

It drives the **controllers as beans** rather than over HTTP. That still runs
everything that matters — `Money` conversion at the boundary, `IdempotencyService`,
the services, the balance check, the real migrated schema — without a servlet
container or a socket per try, which at these tries counts is the difference
between a suite that runs and one that does not. The one thing it gives up is
`ApiExceptionHandler`, so properties assert on the exception thrown rather than
on a status code. That is the more precise assertion, and the status mapping is
already covered by the Phase 1-5 integration tests.

### Bug log

Property-based testing found **one defect** in Phases 1-5. It is a real one:
money could be created.

---

#### BUG-1 — A payment could be both refunded and reversed, returning more than was paid

**Found by** `TransactionSequencePropertyTest.noChainOfOperationsEverCreatesMoney`

**The failing case.** jqwik's original counterexample was a five-step chain at
9,683,895 minor units. Shrunk, it is three operations:

```
currency:       "USD"
paymentMinor:   1
chain:          [REFUND, REVERSE_PAYMENT]
refundPercents: [1]

  sequence: PAY 1 USD
            REFUND 1 -> accepted
            REVERSE_PAYMENT -> accepted

  the payer must never end up better off than before paying
  Expecting actual:
    290516L
  to be less than or equal to:
    0L
```

That is: pay one minor unit, refund it, then reverse the payment's transaction.
The payer ends at **+1** — better off than before they paid. The same defect
exists in the other order, reverse then refund.

**Root cause.** Two guards, each correct in isolation, that did not know about
each other.

- `RefundService` capped refunds at `payment.amountMinor - sum(refunds)`. It had
  no idea a reversal had already returned the money, because a reversal wrote
  nothing to the payment and nothing to `refunds`.
- `ReversalService` refused to reverse a transaction twice, through the UNIQUE
  constraint on `reversals.original_transaction_id`. It had no idea the payment
  had been partly refunded, because it worked in terms of transactions and never
  looked at the payment.

So a reversal returned the whole original amount while a refund returned part of
it, and neither could see the other's work. Every transaction involved balanced
perfectly — the payment, the refund and the reversal each had sum(debits) =
sum(credits) — which is exactly why the Phase 1-5 tests never caught it, and why
the per-transaction invariant alone was never going to. Conservation of money
across a *chain* is a different property, and nothing had been stating it.

**Why the example-based tests missed it.** `RefundReversalFlowIntegrationTest`
covered refunds thoroughly and reversals thoroughly. It never mixed them on the
same payment, because there was no reason to think that combination was
interesting. That is the argument for property-based testing in one sentence.

**The fix.** Make the two operations mutually exclusive on a payment, which is
what they always were semantically: "give some of it back" is a refund, "this
should never have happened" is a reversal, and the second only means anything if
nothing has been given back yet.

- `V6__payment_reversal_status.sql` widens the `payments_status_valid` check to
  admit `REVERSED`. No backfill is needed: no existing row could hold that value,
  because nothing could set it.
- `PaymentStatus` gains `REVERSED`, and `Payment.markReversed()` is the one-way
  transition out of `POSTED`.
- `ReversalService.reverse` now looks up the payment settled by the transaction,
  via `PaymentRepository.findByTransactionIdForUpdate` — taking **the same row
  lock, in the same order, as the refund path**, so a refund and a reversal
  racing on one payment serialise instead of both succeeding. If that payment has
  any refunds it throws `RefundedPaymentCannotBeReversedException` (**422
  `refunded_payment_cannot_be_reversed`**); otherwise it marks the payment
  `REVERSED` in the same database transaction as the negating postings.
- `RefundService`'s existing `status != POSTED` guard then refuses a refund
  against a `REVERSED` payment, with no change needed there.

A refund's *own* transaction is still reversible, and so is a reversal's. The
guard is specifically on a payment's transaction, because a payment is the only
thing carrying a refundable amount.

**The regression tests.** Four, in `RefundReversalFlowIntegrationTest`, holding
the shrunk case at exactly one minor unit:

| Test | Locks in |
|---|---|
| `refundThenReverseIsRejected` | the shrunk counterexample verbatim — pay 0.01, refund 0.01, reversal is 422, payer ends at 0 |
| `reverseThenRefundIsRejected` | the other order — reversal succeeds, refund is refused, payer is not paid back twice |
| `reversingAnUnrefundedPaymentMarksIt` | the ordinary path still works, and the payment reads `REVERSED` in the database |
| `reversingARefundTransactionIsStillAllowed` | the guard did not over-reach: a refund's transaction is still reversible, and the payment stays `POSTED` |

`noChainOfOperationsEverCreatesMoney` passes at 80 tries after the fix. One
existing test — `OutboxKafkaFlowIntegrationTest.refundAndReversalProduceEvents` —
was updated to use two separate payments, because it had been refunding and
reversing the same one incidentally while testing something else entirely.

---

#### DEFECT-1 (Phase 7)

**A javadoc claim about batch publishing that was true on only one of two paths**

Classification: **documentation**. Found by `OutboxChaosTest` scenario 3. The
behaviour it described inaccurately is safe, so this is not a functional defect -
but the sentence read as an unconditional guarantee and it was not one.

`OutboxPublisher.drainOnce()` claimed that "events already confirmed by the
broker keep their `published_at`, and the one that failed is retried next cycle."
That holds when a **send** fails: the loop breaks, the transaction still commits,
and the marks already applied survive. But marks are applied by Hibernate at
commit and the whole drain is one transaction, so when the **transaction** fails
- the database refusing the UPDATE, or the process dying before commit - every
mark in the batch rolls back, including those for events the broker already
accepted.

Scenario 3 measured it: three events, all three sent, the mark UPDATE refused,
all three rows still unpublished, and the next cycle republishing all three for
six deliveries of three events. Consumers deduplicate, so those six deliveries
produced exactly three effects; nothing was lost and nothing double-counted. The
cost is a batch-sized burst of duplicates rather than the single one a send
failure costs - which matters to anyone tuning `batch-size` or reasoning about
duplicate volume during a database blip, and which the old sentence did not
mention.

**The fix** distinguishes the two paths explicitly, says that a transaction
failure republishes the whole batch, notes that this is safe because consumers
deduplicate on `eventId`, and points out that `batch-size` bounds the burst. It
also states plainly what both paths share: nothing is ever marked published that
the broker did not confirm, so no event can be lost.

**The regression** is scenario 3 itself, which pins the measured behaviour
(3 events, 6 deliveries, 3 effects) so a future change to the transaction
boundary cannot quietly alter it without the javadoc being revisited.

**No functional defect was found in Phase 7.** Fifteen scenarios across four
failure families left every invariant intact. That is a weaker result than Phase
6's, and it is reported as it happened rather than padded - the limits of what
fifteen scenarios cover are stated in
[CHAOS_REPORT.md](CHAOS_REPORT.md#functional-defects-none-found).

---

#### Not bugs, but worth writing down

Two behaviours the properties surfaced that were examined and deliberately left
alone:

- **Reversing a refund does not restore refundable capacity.** Reverse a refund's
  transaction and the payer is out of pocket the full amount again, but the
  `refunds` row still stands, so the payment cannot be refunded again for that
  amount. This is *conservative* — it under-refunds rather than over-refunds, and
  no property is violated. Making refundable capacity respond to reversals of
  refunds is a design decision, not a bug fix, and it belongs in a phase that
  decides it deliberately.
- **A reversal is itself reversible.** Nothing marks a reversal transaction
  special, so reversing one reinstates the original movement. That is consistent
  with the stated rule — any transaction may be reversed once — rather than an
  oversight, and it is covered three ways rather than assumed:
  `reversingAReversalReinstatesTheOriginalExactly` pins the depth-2 case by name;
  `reversalChainsConserveMoneyAtAnyDepth` generates depths 1-6 and asserts at
  **every** step that the pair nets to zero, that the payer is never better off
  than before paying, and that the balance alternates *exactly* between 0 and
  −amount (drift of one minor unit at depth five would satisfy conservation and
  still be a bug); and the `REVERSE_LAST_REVERSAL` step puts the same chains
  under `noChainOfOperationsEverCreatesMoney` interleaved with refunds and
  replays — an instrumented run confirmed it is accepted 38 times across the
  class's 240 tries, so it is live coverage rather than a step that always
  skips.

  Note what stays true at even depths, where the money really has moved to the
  payee again: the payment was marked `REVERSED` by the *first* reversal and
  stays that way, so it can never be refunded on top. That is conservative — it
  under-refunds rather than over-refunds — and the depth property asserts the
  refund is refused at every depth rather than leaving it implied.

And one honest note about the properties themselves: JSON that parses but is not
an event envelope (a bare array, a JSON `null`) is **not** covered by
`unparseableMessagesAreSkippedWithoutClaimingAnything`. The consumer documents
what it does with *unparseable* messages and makes no promise about that case, so
asserting a behaviour there would have been inventing a contract rather than
testing one.

---

## Resilience (Phase 7 - ChaosLab)

Phase 6 verified the invariants against randomized inputs. ChaosLab verifies them
against randomized **failures**, which is a different question: a ledger can be
perfectly correct on every input it is given and still lose money the first time
a connection drops mid-write.

**Full detail, scenario by scenario, is in [CHAOS_REPORT.md](CHAOS_REPORT.md).**
The short version:

- **15 scenarios**, in four families - outbox and the dual-write gap, database
  faults, delivery shape, and reconciliation partial failures.
- **No production code was added for the harness.** Every fault is injected at a
  seam that already existed: the pooled `DataSource`, the `KafkaTemplate` that
  `OutboxPublisher` is constructed with, and the injected `Clock`.
- **Deterministic, not random.** Seeds are constants declared by each scenario. A
  chaos test that fails once and then passes teaches people to re-run the build
  instead of reading the failure.
- **One documentation defect found** ([DEFECT-1](#defect-1-phase-7)); no
  functional defect.

### What is injected, and where

| Fault | Seam | Why there |
|---|---|---|
| Dropped connection, statement timeout | `ChaosDataSource` wraps the pooled `DataSource` and fails a nominated JDBC statement | The rollback under test is then PostgreSQL's own. A mocked repository that throws proves the service handles an exception; it cannot prove the database rolled anything back, because no database was involved |
| Broker unavailable; **acknowledgement lost after acceptance** | `ChaosKafkaTemplate` replaces the `KafkaTemplate` | The ack-lost state is the one a real broker will not perform on demand, and it is the one that produces the duplicate the outbox design exists to tolerate |
| Duplicate and out-of-order delivery | The consumers' own public entry points | It is a delivery *shape*, not an injected failure - no machinery needed |
| Malformed and incomplete external data | Settlement rows written directly, plus Phase 5's existing `SettlementFaultService` | The external system owns that table; writing rows is what a misbehaving processor does |
| Time passing | `TickingClock` replaces the injected `Clock` | Sleeping through the reconciliation grace window is slow and, on a loaded machine, flaky |

The harness has **its own self-test**, and that is not paranoia: every scenario
proves something by surviving an injected fault, so all of them would pass -
falsely - if the fault were never injected. `ChaosHarnessTest` asserts that armed
faults really do throw through queries the application makes, that the broker's
three outcomes are genuinely distinct, and that the application reads the
scenario's clock.

### Why the broker is a fake here

The scenarios test *our* handling of the broker contract, not the broker itself.
Phase 4's `OutboxKafkaFlowIntegrationTest` keeps the real-broker proof, against a
real Kafka container. Duplicating it here under a noisier harness would add
runtime without adding evidence, while the states ChaosLab actually needs -
accepted-but-unacknowledged above all - cannot be staged on a real broker at all.

### Running the scenarios

```powershell
mvn test -D"test=*ChaosTest"
```

```powershell
mvn test -D"test=ChaosHarnessTest"
```

> **PowerShell note:** quote the argument as `-D"test=..."`. Unquoted, PowerShell
> expands the `*` before Maven sees it. The quoted form works in `cmd.exe` too;
> in Git Bash use `-Dtest='*ChaosTest'`.

---

## Detection (Phase 8 - statistical)

Phases 6 and 7 asked whether the ledger is correct. This layer asks which
activity on a correct ledger deserves attention — which is a question about
*unusualness*, not about correctness, and needs different tools.

**Full detail is in [DETECTION_REPORT.md](DETECTION_REPORT.md).** The short
version:

- **Five signals**: amount outlier, velocity, burst, reconciliation mismatch
  rate, refund/reversal rate. Each produces a score in `[0,1]`, not a flag, so
  they can be combined and later learned from.
- **Two statistical cores, not five ad-hoc rules.** Robust location/scale for
  amounts; exact discrete tail probabilities for counts and rates.
- **Read-only and query-time.** No new write path, no rolling-statistics table.
- **No ML.** That is the next phase.

### Why not z-scores

Both cores exist to avoid one, for different reasons.

**For amounts, the thing being hunted is the thing that breaks a z-score.**
`(x − mean)/sd` computes both terms from a sample containing the outlier, so one
large payment inflates the standard deviation enough to mask itself. So: median
and **median absolute deviation**, both of which tolerate half the sample being
corrupted before they move.

That brings its own trap, and it is not rare: **MAD is zero whenever more than
half an account's payments share one amount** — subscriptions, fixed fees,
repeated transfers. A naive implementation divides by zero there and calls every
regular-paying account infinitely anomalous. The fallback chain is MAD, then mean
absolute deviation, then a degenerate branch where identical values score exactly
zero and a differing one scores a bounded ceiling.

**For counts, the normal approximation fails where this layer lives.** An account
averaging 0.2 payments an hour that makes 3 scores 6.26 by `(k − λ)/√λ`, which
reads as extraordinary; the exact Poisson answer is `p = 0.001148`, a surprisal
of 2.94, which does not clear the flagging threshold. So the tails are computed
exactly and reported as surprisal, `−log₁₀ p`, putting four signals on one linear
scale.

### The signals

| Signal | Asks | Fires at | Minimum data |
|---|---|---|---|
| Amount outlier | Is a recent payment far from this account's own usual? | modified z ≥ 3.5 | 8 prior payments |
| Velocity | Is it transacting faster than its own rate? | surprisal ≥ 3 | 20 baseline payments |
| Burst | Is activity clustered tighter than its rate explains? | surprisal ≥ 3 | 20 baseline, 3 in window |
| Reconciliation mismatch rate | Are its transactions failing reconciliation unusually often? | surprisal ≥ 3 | 10 transactions |
| Refund/reversal rate | Are its payments coming straight back unusually often? | surprisal ≥ 3 | 10 payments |

Velocity and burst are genuinely different questions: ten payments spread across
an hour and ten inside three seconds have the same hourly count. Burst
Bonferroni-corrects across the windows it scans, because searching for the most
improbable window is a multiple comparison and an uncorrected scan statistic
finds a "burst" in any Poisson process at all.

### Scoring

A weighted sum, renormalised over the signals that had enough data to judge.

> **The weights are unfitted judgment, not learned parameters.** There is no
> labelled data in this phase, so nothing has been validated against known fraud.
> Fitting them is the ML layer's job.

Signals have **three** states, not two: fired, looked and found nothing, and had
nothing to judge with. Collapsing the last two is how a detection layer ends up
quietly reporting that brand-new accounts are its safest.

### Endpoints

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>"
```

```powershell
curl.exe -s "http://localhost:8080/detection/anomalies?minScore=0"
```

> In PowerShell, `curl` is an alias for `Invoke-WebRequest`, which takes
> different arguments — use `curl.exe`.

Both are GET, both return the per-signal breakdown with each signal's reasoning
in words. With unfitted weights, a score nobody can take apart is a score nobody
should act on.

### Known limits, stated rather than implied

The report carries the full posture. The two that matter most: **burst is
aggressive for low-rate accounts**, because a Poisson process is an optimistic
model of human payment behaviour, and **every signal is per-account**, so a
fan-out across many accounts each behaving unremarkably is invisible to all five.

---

## Machine learning (Phase 9 - Isolation Forest)

Phase 8 built five statistical signals with thresholds from the literature. This
phase adds a learned model beside them.

**Full detail is in [ML_DETECTION_REPORT.md](ML_DETECTION_REPORT.md).** The short
version:

- **Isolation Forest, hand-rolled**, with Liu-Ting-Zhou (2008) cited inline in
  the code so the implementation can be checked against the paper line by line.
- **11 features**: Phase 8's five *raw* statistics plus six the statistical layer
  structurally cannot express.
- **Parallel score, never blended** with the statistical composite.
- **Unvalidated.** No labelled data exists, so precision and recall are
  unmeasured — not approximately known, unmeasured.

### Why hand-rolled rather than a library or a sidecar

The reason that actually decides it: this phase must unit test **tree
construction, path-length scoring and the isolation score formula**, and those
tests cannot be written against a library's internals or across a sidecar's
process boundary. A dependency would not have made them inconvenient, it would
have made them unwritable.

Two supporting reasons: the algorithm is ~150 lines and completely specified in a
nine-page paper, and determinism needs the RNG under local control, since a
library's internal use of its own generator is not part of its contract.

### Why raw statistics, not Phase 8's published scores

`Signal.normalise()` clamps everything below threshold to 0 and above saturation
to 1. Right for a human-readable composite, wrong as model input: surprisal 4 and
surprisal 40 become the same number, and that ordering is most of the
information. Same principle as Phase 8 computing at query time rather than from a
stored aggregate — don't hand the next layer data that has already had its
information removed.

Six features cover what Phase 8 cannot see at all. The clearest is **absolute
amount**: every statistical signal is relative to the account's own history,
deliberately, so nothing there can notice that a magnitude is rare across the
whole population.

### The imputation risk, stated as plainly as Phase 8's weight caveat

A Phase 8 statistic is `NaN` when its signal had too little data. Those are
imputed as zero, which conflates *measured and unremarkable* with *not
measurable*. The `dataCompleteness` feature makes that visible to the model.

> **This exposes the problem, it does not solve it.** If most of the training
> population has thin data, the imputed zeros dominate and the model learns the
> imputation rather than the behaviour — separating accounts by how much history
> they have rather than how they behave, while the scores look entirely
> reasonable. On small ledgers that is the likely case, not a remote one.

### Two scores, never one

```
statisticalScore : 0.62      <- explainable signal by signal
mlScore          : 0.71      <- not explainable
agreement        : BOTH_ELEVATED
```

Folding the model in as a sixth signal was rejected because it would need a
sixth unfitted weight, would make the composite partly unexplainable (which
Phase 10's explanation layer would inherit), and would destroy the most useful
thing here: **disagreement**. An account the statistics call quiet and the model
calls extreme is the single most interesting row in the system. Averaging hides
it.

And combining two unvalidated numbers produces one unvalidated number that looks
more authoritative for being single.

| Agreement | Meaning |
|---|---|
| `BOTH_QUIET` | neither elevated |
| `BOTH_ELEVATED` | the only case where the two layers corroborate |
| `STATISTICAL_ONLY` | unusual for this account, ordinary across the population |
| `ML_ONLY` | the row worth reading first |

### Training

Explicit, never lazy — a model appearing as a side effect of the first read would
be trained on whatever the ledger held at that moment, with nobody knowing which
snapshot they got. Below **32 accounts** training is refused rather than done
badly: on a small population almost every point is few and different, so the
model would describe the size of the dataset. "No model" is a distinct answer
from a score of zero.

```powershell
curl.exe -s -X POST "http://localhost:8080/detection/model/train"
```

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>"
```

> In PowerShell `curl` is an alias for `Invoke-WebRequest` — use `curl.exe`.

### What the tests establish, and what they do not

> **Sanity-checking synthetic anomalies-by-construction tells you the model isn't
> broken, not that it's useful on real data.** A forest that separates a point at
> (12, -11) from a Gaussian cloud has shown its arithmetic works. It has shown
> nothing about whether real fraud looks like an outlier in this feature space.

Measuring that needs labelled outcomes this system has never had. Until then the
model is a hypothesis with a test suite, not a validated detector.

---

## Explanation (Phase 10)

Two scores and an enum are not something a reviewer can act on. This phase turns
them into a breakdown, an attribution and a few sentences of prose. It adds no
signal and changes no model: everything explained here was already being
computed.

Full detail in [EXPLANATION_REPORT.md](EXPLANATION_REPORT.md).

### The statistical half: parts that add up to the whole

Each signal already explained itself in Phase 8, in a sentence written by the
code that did the arithmetic. What was missing is how much of *this account's*
composite each one supplied, which depends on what else could be measured — the
same signal at the same score contributes 0.25 of a five-signal composite and
0.56 of a two-signal one.

So each signal now carries an **effective weight** (its fixed weight renormalised
over the applicable set) and a **contribution** (`effectiveWeight × score`), and:

```
sum(contribution) == composite
```

exactly. A breakdown whose parts do not add up to the whole is decoration.
Signals that could not judge stay in the list contributing zero, because a
missing row would read as a signal that looked and found nothing.

### The hard half: explaining an Isolation Forest

A forest publishes a mean path length and nothing else — no coefficients, no
impurity gains. **Every feature-level account of an isolation score is a
reconstruction.** Three were considered:

| Method | Cost | Faithfulness |
|---|---|---|
| Isolation-bit attribution from the point's real paths | one extra traversal per tree | high — reads the splits this point actually crossed |
| Leave-one-feature-out re-scoring | 11× scoring | medium — explains a substituted point, not this one |
| Population-outlier ranking | trivial | low — can name a feature no tree split on |

The first was chosen. Walk the point down every tree again; at an internal node
holding `n` rows the point descends into a child holding `m`, so that split is
credited `log2(n/m)` **bits of isolation** against the feature it split on.
Subtract one bit per edge — what an even split would have given — and the
remainder, **excess bits**, is positive exactly when a feature isolated this
account faster than a coin flip.

Bits rather than edges is what makes it an explanation. Crediting one unit per
edge would attribute *depth*, and depth is what normal points accumulate, so the
top of that ranking would be whichever feature the random selection happened to
draw most often.

**What it is not:** the credits do not sum to the score and are not Shapley
values; correlated features steal credit from each other, since whichever was
drawn first did the separating; and it is a Monte-Carlo estimate, damped by
averaging 150 trees and deterministic under the fixed seed. All four caveats are
in the report and in the per-account caveat list.

Attribution can name a feature but never a direction — a split is a threshold.
So each attributed feature also carries its percentile against the training
population, in separate fields, labelled as **context rather than the model's
reasoning**.

Internal nodes now record their subsample size, which the scoring path never
reads. No split, path or score changed.

### Disagreement is surfaced, not smoothed over

The easy output is one narrative per account written so that two layers
disagreeing reads like two layers agreeing. That would undo Phase 9's central
decision at the presentation layer. The four agreement states get four different
sentences, and a test asserts they differ.

Saying "the statistical layer cannot see this" needs to be true, so each feature
is mapped onto that layer with three levels of visibility, not two:

- **the signal's own statistic** — the first five features;
- **the same axis, differently measured** — `recentPaymentCount` is not invisible
  to the velocity signal, which counts the same payments, but velocity asks
  whether the count is high *for this account* while the raw count compares
  *across accounts*;
- **outside it entirely** — `log10LargestRecentAmount` and
  `log10SecondsSinceLastPayment`, the two Phase 9 added precisely because Phase 8
  structurally cannot express them.

Only the third licenses the claim that no signal looks at something.
`corroborated` is a separate flag from `BOTH_ELEVATED`: two elevated scores
resting on unrelated evidence look like confirmation in a ranking and are not.

Writing the worked examples found two cases where the narrative claimed more
than the numbers supported — a signal that fired at full strength but was
averaged below the threshold being reported as a disagreement, and partial
corroboration reading as complete. Both are in the report's findings section.

### Summaries are templates, deliberately

No LLM call. Determinism is a line Phases 7–9 all hold and the tests depend on
it; the required hedging is easier to guarantee in a template than to police in
generated text; and the content is a closed set of already-computed facts that a
model would only rephrase.

Every sentence describes a departure from a **named reference** — this account's
own history, or the population the model trained on. A test asserts that no
summary ever contains any of fifteen words asserting wrongdoing or certainty.
Caveats travel as their own list rather than as a closing paragraph, because a
limitation folded into prose is a limitation nobody reads.

### Endpoints

The digest rides on the existing reads; the detail has its own endpoint, because
four kilobytes of attribution per row drowns a fifty-account ranking.

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>/explanation"
```

The summary travels in full on the digest, closing qualification included, so a
caller that never follows the link still cannot read an unqualified finding.

### The caveat, still standing

> **An explanation describes what the models computed. It is not evidence about
> whether an account's behaviour is actually anomalous, and it does not become so
> by being specific.** Neither score is validated; the weights are unfitted; the
> thresholds are conventions. A confident-sounding explanation of an unvalidated
> score is still an unvalidated score.

---

## LLM narratives (Phase 11 - GroqCloud)

Phase 10's summaries were templates: deterministic, stilted in places, and
incapable of saying anything the numbers did not. This phase lets a hosted model
write them, and spends almost all of its code making sure that change cannot
cost anything.

Full detail in [LLM_EXPLANATION_REPORT.md](LLM_EXPLANATION_REPORT.md).

### It runs without a key, and that is not a degraded mode

With no `LEDGERGUARD_GROQ_API_KEY` set, every narrative comes from the Phase 10
templates and the response says `"narrativeSource": "TEMPLATE"`. That is how the
test suite runs and how a fresh checkout behaves. The object graph is identical
either way — one step declines to run — so the fallback path is the ordinary
path rather than a second configuration nobody exercises.

### The model may restate the evidence and nothing else

The prompt's load-bearing line is the one that says what job the model has.
Asked to *explain why this account is anomalous*, a model reasons: it weighs, it
infers motive, and it reaches for "likely". Asked to *rewrite this record as
prose, adding nothing*, the same model does something much closer to what is
wanted, because the second framing leaves no room for a conclusion the record
does not contain.

The record is built from Phase 10's `AccountExplanation` and nothing else — no
second pass over the ledger — so a fact in the prose either appears there or was
invented. Worth stating because it is a property rather than luck: **no
user-controlled text reaches the model.** No payment descriptions, no
counterparty names; Phase 8 never collected any. An attacker who controls
payments controls, at most, some doubles.

### Every name and number is checked before anyone reads it

A prompt is a request; a validator is a guarantee. Five layers, none
short-circuiting:

| Layer | Rejects |
|---|---|
| Structure | empty, too short or long, markdown, `Here is a summary:` |
| Vocabulary | Phase 10's fifteen wrongdoing/certainty words |
| Identifiers | a real signal or feature this account's evidence never carried |
| Identifiers | a token shaped like one of ours that exists nowhere |
| Numbers | any number that is not a truthful rendering of an evidence value |
| State | prose contradicting the agreement state it was given |

Numbers are the hard one. Exact matching cannot work — the evidence holds 0.556
and a good sentence says "0.56", or "56%", or "0.6", all truthful. So a stated
number is grounded when some evidence value, **rounded to the precision the
narrative chose**, equals it.

**What it cannot catch:** grounded parts assembled into a misleading whole — the
right number attributed to the wrong signal. Judging that needs understanding the
sentence, and anything able to understand it would need validating in turn. That
residue never reaches zero, which is why the template fallback and the source
discriminator both exist.

### Failure is logged, never surfaced

Timeout, rate limit, bad key, open breaker, fabricated number — every one of them
returns the complete Phase 10 narrative and marks the source `TEMPLATE`. No error
field, no banner. A reviewer looking at a flagged account is doing real work, and
an interruption about a model provider tells them nothing they can act on.

`NarrativeSource` has two values and deliberately not three: a template served
after a timeout is the same artefact as one served with no key configured, and
naming the difference on the wire would make an ordinary degradation look like an
incident.

Resilience: 1.5 s connect and 4 s read timeouts, two attempts 200 ms apart, and a
breaker that opens after three consecutive failures for sixty seconds. The
breaker is what turns a provider outage from "every request is four seconds
slower" into "three slow requests, then nothing".

### Determinism, honestly

Phases 7–10 promised the same ledger gives the same output. **This phase cannot.**
Temperature 0 is not determinism: hosted inference varies with batching and
scheduling, and providers update weights behind a stable model id.

So temperature is zero *and* narratives are cached on
`SHA-256(model | promptVersion | evidence JSON)`. What that buys is narrower than
determinism and is most of what determinism protected: the same evidence returns
the same words. `asOf` and the account id are deliberately out of the key — the
narrative describes numbers, so identical numbers should not pay twice to differ
— and because every number the prose could mention is *in* the key, stale prose
about moved numbers is not representable.

### One endpoint, because calls cost money

The model runs on `GET /detection/accounts/{id}/explanation` and nowhere else.
The ranking returns many accounts and one call per row would mean fifty calls to
help someone choose the one account to open. Here a human has already chosen, so
spend scales with attention rather than page size.

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>/explanation"
```

The deterministic version is always one parameter away, for an audit trail or a
second opinion on prose that reads oddly:

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>/explanation?narrative=template"
```

### Model choice is falsifiable

`llama-3.1-8b-instant` by default, in configuration rather than code. The task is
constrained rewriting, not reasoning — the hard thinking was done by Phases 8–10
— so the cheapest and fastest model is the right starting point. Whether that was
right is measured, not asserted: the service reports a **rejection rate**, and
the validator's reason codes say whether the answer is a better prompt
(`PREAMBLE`, `MARKUP`) or a bigger model (`UNGROUNDED_NUMBER`).

### Secrets

The key comes from `LEDGERGUARD_GROQ_API_KEY`, the same environment-variable
pattern the database and Kafka credentials have used since Phase 1. It is sent in
an `Authorization` header and **never logged** — a test asserts no failure
message from any status code contains the key or the word `Bearer`, because an
error message ends up in a log and a key in a log is a leaked key. `.env` is
gitignored; [.env.example](.env.example) is committed with an empty value.

### The caveat, sharpened

> **Better prose is not better ground truth.** An LLM narrative describes the
> same unvalidated statistical and ML outputs Phase 10 described. It reads more
> fluently and knows nothing more. Fluent prose is more persuasive than a
> template without being more correct, so this upgrade carries a real risk: it
> makes the output *easier to believe* without making it *more believable*. That
> asymmetry is why the validator is strict, why the source is on every response,
> and why the forbidden vocabulary is enforced twice.

---

## Validation (Phase 12 - labels)

Four phases ended with "these scores are unvalidated, because there is no
labelled data". This phase builds the machinery that ends that sentence, runs
it, and reports what it found.

Full detail in [VALIDATION_REPORT.md](VALIDATION_REPORT.md).

### What it found

**Three of the five statistical signals must fire at saturation before a
fully-measured account is elevated at all.** That is arithmetic, not a
measurement that might have gone another way:

| Signals firing at 1.00 | Composite (all five applicable) | Elevated at 0.50? |
|---|---|---|
| `amount_outlier` alone (heaviest, 0.25) | 0.25 | no |
| the two heaviest (0.25 + 0.20) | 0.45 | no |
| the three lightest (0.20 + 0.20 + 0.15) | 0.55 | yes |

An account whose largest payment is twelve thousand robust deviations from its
own history scores 0.25 and is not flagged.

Phase 8 documented this renormalisation as protection for thin-history accounts.
The consequence in the other direction was never written down: a **thin** account
where two signals apply needs **one** to fire; a **fully-measured** account needs
**three**. The composite is easier to trip with less evidence, not more. Phase 10
met the same arithmetic as a narrative problem and called it dilution; here it is
a detection problem with a number on it.

Nothing was tuned in response — this phase measures and does not fit, because
fitting against the label set you then evaluate on measures nothing.
`CompositeCeilingTest` pins the property so a later phase has to confront it.

### Labels come from three places, never pooled

**A dispute feed**, on the Phase 5 simulator pattern: consumes Kafka, applies its
own rules in its own table, no access to `payments` or any score. A label source
derived from the data being judged is a mirror, and a detector evaluated against
a mirror passes by construction. It is also *late* — disputes are dated 30-90
days out and become labels only when that date arrives — and selective, since
only `FRAUDULENT` counts. A cardholder who never received goods has a dispute
with a merchant, not evidence of fraud.

**Human review**, the first write path in a layer read-only since Phase 8. It
writes an opinion, never a score. Append-only, like postings.

**Synthetic**, for exercising the harness, and with **deliberately no endpoint**:
nothing in a running system can write one. Including them in a report produces a
warning saying the result is circular.

### The four ways this goes wrong, designed against

**Verification bias.** Reviewers left alone label what the detector shows them,
which makes every missed account invisible and turns recall into precision under
another name. So the queue draws from a `FLAGGED` pool and a random `AUDIT` pool
of accounts the detector did *not* surface. This cannot be retrofitted. With zero
audit labels the evaluator reports recall as **unmeasurable** rather than
printing a 1.0 it got by construction.

**Temporal leakage.** A chargeback raised in November is evidence about September.
Every account is scored as of its label, never as of now — otherwise the detector
is handed every consequence of the fraud, including the refunds and
reconciliation incidents the fraud itself caused.

**Anchoring.** The review queue is blind by default; seeing the scores is what you
ask for, not what you opt out of. Every label records whether they were visible.

**Reviewer disagreement is the ceiling.** If two people disagree on a fifth of
accounts, no detector is meaningfully 95% accurate against them. An unmeasured
agreement rate is reported as absent rather than perfect.

### Statistics done properly

Design-based weighting, so one audit account reviewed out of two hundred stands
for all two hundred. **Wilson intervals**, because the textbook formula reports
zero width at ten out of ten and would publish certainty from ten accounts. **PR
curves, not ROC**, because under fraud's class imbalance the false positive rate
has a vast denominator and a mediocre detector posts an impressive 0.95.

### The synthetic benchmark

Circular by construction, and reported as such. 50 accounts, 10 anomalous by
design:

```
-- statistical composite, flagging at 0.50 --
  precision=1.000 [0.21, 1.00]   recall=0.100 [0.02, 0.40]
  average precision=0.280  (chance=0.200)
  best F1 at threshold 0.000: precision=0.200 recall=1.000

-- isolation score, flagging at 0.60 --
  precision=1.000 [0.57, 1.00]   recall=0.500 [0.24, 0.76]
  average precision=0.600  (chance=0.200)
```

The statistical layer missed nine of ten anomalies it was built to catch, and its
best operating point is "flag everything" — a threshold at which flagging nothing
selectively beats flagging selectively is a threshold in the wrong place. Average
precision of 0.280 against chance of 0.200 says the ranking is real and the cut
discards most of it, which is exactly the distinction a PR curve exists to make.

Phase 9's claim that `ML_ONLY` is "the row worth reading first" got its first
evidence: anomalous 4 times out of 4, against 11% for `BOTH_QUIET`. Also n=4, on
synthetic data, interval [0.51, 1.00] — the weakest kind of support, recorded as
such.

### Endpoints

```powershell
curl.exe -s "http://localhost:8080/validation/review/next?stratum=AUDIT"
```

```powershell
curl.exe -s "http://localhost:8080/validation/report"
```

Warnings come first in that payload, not as a footer, because a reader who skips
them will quote precision from thirty accounts as a property of the detector.

### The caveat, finally narrowing

> **The detector is still unvalidated against reality** — no real fraud has ever
> passed through this ledger. But what was measured is a *structural* property,
> and structural properties need no representative sample: the composite ceiling
> holds for every account, and would hold with a million real labels. The
> synthetic figures measure the harness, not the detector. Every real figure will
> arrive with an interval, and the early ones will be wide enough to be
> embarrassing — which is the correct output for thirty labels.

---

## The composite ceiling fix (Phase 13)

Phase 12 found that the composite could not flag an account on one signal
however extreme it was. This phase characterises that exactly, replaces the
aggregation, and reports what it did and did not change.

Full detail in
[COMPOSITE_CEILING_FIX_REPORT.md](COMPOSITE_CEILING_FIX_REPORT.md).

### The defect, enumerated rather than sampled

All 31 applicable sets against every saturating subset:

| Applicable | Min saturated to flag | Can one signal ever flag? |
|---|---|---|
| 1 | 1 of 1 (composite read **1.00**) | yes |
| 2 | 1 of 2 | yes |
| 3 | 2 of 3 | **no** |
| 4 | 2 of 4 | **no** |
| 5 | 3 of 5 | **no** |

A cliff, not a ramp — and it falls one signal earlier than Phase 12 implied.
Four applicable needs two, not three. The property that matters: a single
signal's ability to flag an account **vanished the moment a third signal became
measurable**, so accruing history could only ever lower an account's composite.

### The fix

```
composite = ( Σ over applicable signals of  wᵢ · sᵢ³ ) ^ ⅓
```

A weighted power mean of degree three, **unrenormalised**.

**The exponent is derived, not chosen.** The guarantee "any one saturated signal
elevates any account" needs `w_min^(1/p) ≥ 0.50`; with the lightest weight at
0.15 that is `p ≥ ln(0.15)/ln(0.50) = 2.74`, so three is the smallest integer
that works. Change a weight or the threshold and the derivation moves with them.
No new unfitted constant enters the system.

**Dropping renormalisation is the load-bearing half.** Phase 8 renormalised
because otherwise a two-signal account "could never exceed 0.45 however extreme"
— true at degree one. At degree three two saturated signals reach 0.766 unaided,
so the power transform already does that job and keeping both compensates twice,
which is what recreated the inversion.

Only this candidate has a *flat* difficulty curve. A renormalised power mean
closes the ceiling too but leaves thin accounts easier to flag than
fully-measured ones, which was Phase 12's actual complaint.

### What the number means now

- It is a function of **what fired**, not of what fraction of the measurable
  evidence fired. Completeness lives in `applicableSignals` / `wellEvidenced`,
  which have travelled beside the score since Phase 8 for exactly this purpose.
- A thin account **caps below 1.0** — two saturated signals reach 0.766.
- Phase 8's documented pathology is retired: one applicable signal, saturated,
  read **1.00** and now reads **0.63**.
- Phase 10's `sum(contribution) == composite` becomes **shares summing to 1.0**,
  because the parts of a power mean sum to the composite cubed. Still exact,
  and it now matches the language the ML attribution already used.

### Thin history did not get easier

Stating this honestly took a failing test first — one pair *did* get easier
(0.900 → 0.794). The true property is the floor:

| | Cheapest single-signal score that flags anything |
|---|---|
| Legacy | **0.500** (one applicable signal) |
| Phase 13 | **0.794** (every configuration) |

An account with almost no history used to be the cheapest in the system to flag.
That floor rose by nearly sixty per cent and is now identical everywhere.

### Two defects in Phase 12's benchmark

Re-running it to measure the fix found that the benchmark had been measuring
itself. The bursts were created inside the per-account loop, which then advanced
the clock 150 hours, so nine of the ten anomalies had **no payments left in the
recent window** when scored. Histories were also built one account at a time,
pushing the earliest outside the **thirty-day baseline**.

Corrected, statistical recall goes 0.100 → 1.000 — and none of that is the
aggregation. Scored through both functions on identical signal outputs: **10/10
either way.**

That is the honest result. The ceiling bites from three applicable signals
upward, and an account anomalous on one axis has *two* — burst needs three
recent payments, the rate signals need ten recent transactions. Reaching the
ceiling on a real ledger needs a high-volume account making one out-of-character
payment, which would take ~7,000 baseline payments to simulate. So the fix is
proven where it can be proven exactly, from the weights alone, and the
ledger-level test records the boundary rather than being contorted until the
number moves.

### The caveat

> Nothing here validates the detector. This phase fixed a defect in how evidence
> is **combined**; it did not establish that the evidence is any good. The
> weights remain unfitted judgement, and the exponent is derived *from* them, so
> it inherits their arbitrariness exactly. No weight or threshold was tuned
> against Phase 12's labels — that stays deferred, and still gated on label
> volume.

---

## Running it locally

### Prerequisites

- **JDK 21**
- **Maven 3.9+**
- **Docker** (for the local Postgres and Kafka, and for the integration tests)

### JAVA_HOME — normally nothing to do

This machine has both JDK 17 and JDK 21 installed. Installing JDK 21 set the
system-wide `JAVA_HOME` to it and put its `bin` first on the system PATH, so a
**newly opened** terminal already builds with Java 21. Check with:

```
mvn -v
```

It should report `Java version: 21.0.12.1`.

**If it reports 17**, your terminal was opened before JDK 21 was installed and is
carrying a stale copy of the environment. Close it and open a new one — that
fixes it. Only if you need to force it inside one existing window, the syntax
depends on your shell:

| Shell | Command |
|---|---|
| Command Prompt | `set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"` |
| PowerShell | `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"` |
| Git Bash / WSL | `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"` |

In Command Prompt the quotes wrap the whole `NAME=value` pair — that keeps the
space in "Program Files" from breaking it without putting literal quote
characters into the value. That form lasts for that one window only.

### Maven location

Maven lives at `C:\tools\apache-maven-3.9.11`, deliberately outside OneDrive. An
earlier install under `OneDrive\Desktop\...` was a problem: OneDrive Files
On-Demand can dehydrate the jars into cloud-only placeholders, so a build stalls
waiting on a download or fails outright when offline. Keep build tooling and the
local repository (`C:\Users\vahin\.m2`) out of synced folders.

### 1. Start PostgreSQL

```bash
docker compose up -d
```

That runs two containers: `postgres:16-alpine` on **localhost:5432** with
database `ledgerguard`, user `ledgerguard`, password `ledgerguard`, and
`apache/kafka:3.9.0` in KRaft mode on **localhost:9092**. Both match the
defaults in `application.yml`.

Wait for both to report healthy before starting the app:

```bash
docker compose ps
```

The app starts fine without Kafka — that is what the outbox is for — but the
failure demo below is easier to follow if it starts healthy.

To point at a different database instead, override the environment variables:

| Variable | Default |
|---|---|
| `LEDGERGUARD_DB_URL` | `jdbc:postgresql://localhost:5432/ledgerguard` |
| `LEDGERGUARD_DB_USER` | `ledgerguard` |
| `LEDGERGUARD_DB_PASSWORD` | `ledgerguard` |
| `LEDGERGUARD_PORT` | `8080` |
| `LEDGERGUARD_KAFKA_BOOTSTRAP` | `localhost:9092` |
| `LEDGERGUARD_KAFKA_GROUP` | `ledgerguard-ledger-events` |

### 2. Migrations

**Nothing to run by hand.** Flyway applies every migration in `db/migration`
automatically at startup: `V1__init.sql` creates `accounts`, `transactions`,
`payments` and `postings`; `V2__refunds_and_reversals.sql` adds `refunds` and
`reversals`; `V3__idempotency.sql` adds `idempotency_keys`;
`V4__outbox.sql` adds `outbox_events` and `processed_events`;
`V5__reconciliation.sql` adds `settlement_records`, `reconciliation_runs` and
`reconciliation_incidents`; `V6__payment_reversal_status.sql` widens the
`payments.status` check constraint to admit `REVERSED`;
`V7__detection_indexes.sql` adds two read indexes for the detection layer. All of
them arrive with their foreign keys, not-null and check constraints.

Hibernate is set to `ddl-auto: validate`, so Flyway owns the schema outright and
the app refuses to start if the JPA mappings and the migrated schema disagree.

To inspect migration state:

```bash
docker exec -it ledgerguard-postgres psql -U ledgerguard -d ledgerguard -c "SELECT version, description, success FROM flyway_schema_history;"
```

### 3. Start the app

```bash
mvn spring-boot:run
```

It listens on <http://localhost:8080>.

### 4. Run the tests

```bash
mvn test
```

536 tests across fifty-four classes — 108 example-based, 38 jqwik properties
that between them run tens of thousands of generated cases, 22 ChaosLab tests (15
fault-injection scenarios plus a 7-test harness self-test), 65 statistical
detection tests, 47 ML detection tests, 101 explanation tests, 87 LLM narrative
tests and 68 validation tests (including the Phase 13 ceiling characterisation):

| Class | Tests | Covers |
|---|---|---|
| `TransactionServiceBalanceTest` | 11 | the Σ debits = Σ credits invariant, no database |
| `PostingImmutabilityTest` | 7 | no mutation path onto a posting exists |
| `PaymentFlowIntegrationTest` | 6 | payment → balance, end to end |
| `RefundServiceTest` | 6 | the refund cap and posting direction |
| `ReversalServiceTest` | 5 | reverse-once, and exactness of the negation |
| `RefundReversalFlowIntegrationTest` | 10 | refund/reversal flows, the atomicity proof, and the four BUG-1 regressions |
| `RequestFingerprintTest` | 10 | key-order independence, and what must change the fingerprint |
| `IdempotencyFlowIntegrationTest` | 10 | replay, conflict, scoping, and the concurrency race |
| `OutboxRecorderTest` | 7 | envelope shape, stable event ids, integer amounts on the wire |
| `OutboxKafkaFlowIntegrationTest` | 6 | publish, consume once, redelivery, and outbox atomicity |
| `ReconcilerTest` | 21 | all six classifications, their edge cases, and the severity scheme |
| `ReconciliationFlowIntegrationTest` | 9 | each discrepancy injected end to end, evidence linkage, and run-to-run dedupe |

Phase 6 property tests (see [Verification](#verification-phase-6) for the full
property-to-generator map):

| Class | Properties | Runs against | Covers |
|---|---|---|---|
| `BalanceInvariantPropertyTest` | 6 | in-memory | the balance check as a pure function: accepted iff every currency nets to zero |
| `MoneyPropertyTest` | 8 | in-memory | minor-unit round trips, zero- and three-decimal currencies, sub-minor rejection, unusable codes, overflow |
| `LedgerPersistencePropertyTest` | 5 | Postgres | the invariant as stored data, currency isolation, and refusals writing nothing |
| `IdempotencyPropertyTest` | 4 | Postgres | exactly one financial effect per key, sequential and concurrent |
| `RefundCapPropertyTest` | 3 | Postgres | the cap across arbitrary sequences of partial refunds |
| `ReversalPropertyTest` | 5 | Postgres | exact negation, reverse-once, and reversal chains at arbitrary depth |
| `TransactionSequencePropertyTest` | 3 | Postgres | whole chains of legal and illegal operations — where BUG-1 was found |
| `EventReplayPropertyTest` | 4 | Postgres | at-least-once delivery, exactly-once effect |

Phase 7 chaos scenarios (see [CHAOS_REPORT.md](CHAOS_REPORT.md) for what each one
attacks and the invariant it asserts):

| Class | Tests | Covers |
|---|---|---|
| `ChaosHarnessTest` | 7 | the harness itself: that injected faults are real and not silently inert |
| `OutboxChaosTest` | 5 | broker down, ack lost, crash between send and mark, the dual-write gap itself, partial batch failure |
| `DatabaseFaultChaosTest` | 4 | dropped connections and timeouts mid-payment, mid-refund and mid-reconciliation |
| `DeliveryChaosTest` | 3 | duplicate, out-of-order and interleaved delivery against both consumers |
| `ReconciliationChaosTest` | 3 | malformed external records, a truncated feed, an incomplete event payload |

Phase 8 detection tests (see [DETECTION_REPORT.md](DETECTION_REPORT.md) for the
statistical basis of each signal):

| Class | Tests | Covers |
|---|---|---|
| `RobustStatisticsTest` | 12 | median, MAD, the mean-deviation fallback and the degenerate case |
| `DiscreteTailsTest` | 15 | Poisson and binomial tails against hand-computed values, surprisal, Laplace smoothing |
| `SignalUnitTest` | 24 | each signal: an obvious anomaly, a clear non-anomaly, and boundary cases |
| `AnomalyScorerTest` | 7 | weighting, renormalisation, and the thin-evidence trade |
| `DetectionFlowIntegrationTest` | 7 | signals against a real ledger the real reconciler has run over |

Phase 9 ML detection tests (see [ML_DETECTION_REPORT.md](ML_DETECTION_REPORT.md)):

| Class | Tests | Covers |
|---|---|---|
| `IsolationForestTest` | 20 | tree construction, path length, the score formula against hand arithmetic, determinism |
| `FeatureExtractorTest` | 12 | the feature pipeline, imputation contract, log transforms, signed z-score |
| `AgreementTest` | 7 | the four-way relationship and why its two thresholds differ |
| `MlDetectionFlowIntegrationTest` | 8 | training, the population gate, and determinism end to end on a real ledger |

Phase 10 explanation tests (see [EXPLANATION_REPORT.md](EXPLANATION_REPORT.md)):

| Class | Tests | Covers |
|---|---|---|
| `StatisticalExplanationTest` | 12 | contributions summing to the composite, renormalisation made visible, every signal attributed to itself |
| `AttributionTest` | 20 | a point extreme on one feature attributed to that feature, once per feature; an ordinary point attributed to nothing; the credit rule and determinism |
| `PopulationProfileTest` | 6 | the percentile context, its boundaries and its ties |
| `FeatureProvenanceTest` | 26 | the feature-to-signal map pinned against the model's own feature order |
| `ReconciliationTest` | 13 | all four agreement states, corroboration that is not score agreement, dilution, and four narratives that differ |
| `SummaryWriterTest` | 14 | the forbidden vocabulary, the qualification that always travels, the caveats each account earns |
| `ExplanationFlowIntegrationTest` | 10 | explanations agreeing with the scores they explain, on a real ledger, and the narrative falling back to the template with no API key |

Phase 11 LLM narrative tests (see
[LLM_EXPLANATION_REPORT.md](LLM_EXPLANATION_REPORT.md)). None of them makes a
network call:

| Class | Tests | Covers |
|---|---|---|
| `NarrativeEvidenceTest` | 10 | the payload is complete and closed, and its JSON is stable enough to key a cache on |
| `NumericGroundingTest` | 10 | truthful roundings and percentages accepted, wrong values rejected |
| `NarrativeValidatorTest` | 24 | fabricated and ungrounded identifiers, wrong numbers, banned vocabulary, preamble, markdown, state contradictions |
| `HttpGroqClientTest` | 10 | request shape, unknown response fields, which statuses are retryable, and that the key never reaches an error message |
| `GroqGatewayTest` | 10 | the retry budget, the breaker opening, its cooldown and its trial call |
| `NarrativeCacheTest` | 8 | what belongs in the key, what pointedly does not, and LRU eviction |
| `NarrativeServiceTest` | 15 | every failure path serving a complete template narrative, and the cache serving repeats |

Phase 12 validation tests (see [VALIDATION_REPORT.md](VALIDATION_REPORT.md)):

| Class | Tests | Covers |
|---|---|---|
| `WilsonTest` | 7 | the interval that stops ten-out-of-ten being published as certainty |
| `ConfusionMatrixTest` | 6 | precision, recall, base rate and lift, and every undefined quantity refusing to be zero |
| `PrecisionRecallCurveTest` | 9 | perfect, inverted and random rankings; ties; weights; average precision against chance |
| `AccountLabelTest` | 9 | what a label must say about itself before it is allowed to exist |
| `CompositeCeilingTest` | 18 | the ceiling closed over all 31 applicable sets, the inversion gone, the exponent's derivation, and the floor that guards thin history |
| `ValidationFlowIntegrationTest` | 19 | leakage-safe scoring, recall refused without an audit stratum, dispute maturity, weighting, the corrected benchmark and the aggregation before/after |

The integration tests start their own throwaway PostgreSQL via Testcontainers
and run the real Flyway migrations against it — no in-memory database stand-in,
because the schema constraints are part of the product.

#### Running just the property suite

Every property class is tagged `property`, so the whole suite isolates by tag:

```bash
mvn test -Dgroups=property
```

Or by class-name pattern, which does the same thing here since every property
class ends in `PropertyTest`:

```bash
mvn test -Dtest='*PropertyTest'
```

The fourteen in-memory properties need no Docker at all and finish in about a
second:

```bash
mvn test -Dtest='MoneyPropertyTest,BalanceInvariantPropertyTest'
```

One class at a time, when a property fails and you want its output uncluttered:

```bash
mvn test -Dtest=TransactionSequencePropertyTest
```

jqwik prints the seed for every property it runs. To re-run a failing property on
exactly the inputs that broke it, put that seed on the `@Property` annotation:
`@Property(tries = 80, seed = "3234359065474358181")`. By default jqwik also
re-runs the last failing sample first on the next run, so a fixed bug is
re-checked against its own counterexample before anything else is generated.

`OutboxKafkaFlowIntegrationTest` also starts a real Kafka broker, because the
delivery guarantee is a property of two systems and their failure modes and
cannot be tested against a mock. It uses Testcontainers' `ConfluentKafkaContainer`
rather than the `apache/kafka` image used in `docker-compose.yml`: that image
formats its storage before Testcontainers can inject the mapped port, so
`advertised.listeners` is still `0.0.0.0` and the broker refuses to start. Both
are KRaft; the difference is only in how the port is negotiated.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/accounts` | Create an account. |
| `GET` | `/accounts/{id}/balance` | Balance derived from postings. |
| `POST` | `/payments` | Payment → transaction → balanced DEBIT/CREDIT pair. Returns the transaction with its postings. |
| `POST` | `/payments/{id}/refunds` | Refund all or part of a payment as a new, opposite transaction. |
| `POST` | `/transactions/{id}/reversals` | Fully reverse a transaction, once. Body optional. |
| `POST` | `/reconciliation/runs` | Run a reconciliation pass now and return what it found. |
| `GET` | `/reconciliation/incidents` | Filter incidents by `type`, `severity`, `status`, `transactionId`. |
| `POST` | `/reconciliation/incidents/{id}/resolve` | Mark an incident resolved. |
| `GET` | `/detection/accounts/{id}` | Anomaly score for one account, with every signal's contribution and a summary. |
| `GET` | `/detection/accounts/{id}/explanation` | Why it scores that: full signal breakdown, per-feature model attribution, how the two layers relate, caveats, and a narrative marked `TEMPLATE` or `LLM`. `?narrative=template` forces the deterministic one. |
| `GET` | `/detection/anomalies` | Accounts either layer flags, worst first. Not ranked by a blended score, because there is no blended score. |
| `POST` | `/detection/model/train` | Train an Isolation Forest on the ledger as it stands. Refused below 32 accounts. |
| `GET` | `/detection/model` | What model is loaded, if any. |
| `POST` | `/validation/labels` | Record a reviewer's verdict on an account. Append-only, replay-safe. |
| `GET` | `/validation/labels/{accountId}` | Every label on an account, including superseded ones. |
| `GET` | `/validation/review/next` | Next account to judge, blind by default. `?stratum=FLAGGED\|AUDIT`. |
| `GET` | `/validation/review/census` | How many accounts sit in each sampling pool. |
| `GET` | `/validation/report` | Precision, recall, PR curves and the warnings that qualify them. |
| `POST` | `/validation/labels/from-disputes` | Turn matured chargebacks into labels. Explicit, never scheduled. |
| `POST` | `/admin/disputes` | Raise a chargeback, for demos. |
| `GET` | `/admin/disputes` | What the simulated card scheme believes. |
| `POST` | `/admin/settlement/faults` | Make the simulated processor misbehave, for demos. |
| `GET` | `/admin/settlement/records` | Inspect what the external world currently believes. |

The three money-moving write endpoints (`/payments`, refunds, reversals)
**require an `Idempotency-Key` header**. `POST /accounts`, the balance read,
and the Phase 5 reconciliation and admin endpoints do not: none of them move
money, so running one twice reports the same facts twice rather than paying
twice.

`POST /accounts` is not itself a Phase 1 deliverable; it exists because the
payment flow needs two accounts to exist before it can be exercised at all.

### Error responses

| Status | When |
|---|---|
| `400` | Unknown currency, amount finer than the currency minor unit, currency mismatch against the account, failed field validation. |
| `400` | Body that is not parseable JSON — `error: "malformed_request_body"`, with the parser detail in `details`. Usually a shell quoting mistake; see the note in the demo section. |
| `400` | Missing `Idempotency-Key` on a write endpoint (`idempotency_key_required`). |
| `404` | Unknown account (`account_not_found`), payment (`payment_not_found`) or transaction (`transaction_not_found`). |
| `409` | An idempotency key reused with a different request (`idempotency_key_conflict`). |
| `422` | Postings do not balance (`unbalanced_transaction`). |
| `422` | Refund would exceed what remains refundable (`refund_amount_exceeded`). |
| `422` | Transaction has already been reversed (`transaction_already_reversed`). |
| `422` | Reversing a payment that has already been refunded (`refunded_payment_cannot_be_reversed`) — doing both would return more than was paid. Added in Phase 6; see [BUG-1](#bug-1--a-payment-could-be-both-refunded-and-reversed-returning-more-than-was-paid). |
| `422` | Training a model on too small a population (`insufficient_training_data`) — a forest over a handful of accounts describes the size of the dataset, not behaviour. |

Every error uses the same shape — `error`, `message`, `details`, `timestamp` —
including unparseable bodies, which would otherwise fall through to the
framework default shape and come back looking nothing like the rest of the API.

The 422s share a character: the request was well formed, but committing it
would have broken a ledger rule. The 409 is deliberately *not* one of them —
reusing a key is a conflict with existing state, not a ledger refusal. `POST /payments` cannot itself produce the
`unbalanced_transaction` one, because it always constructs an equal-and-opposite
pair — that guard exists for *any* caller of `TransactionService`, including the
refund and reversal paths added in Phase 2, and is covered by
`TransactionServiceBalanceTest`.

---

## Verifying the demo flow by hand

With Postgres and the app running (steps 1 and 3 above).

> **Shell note:** the commands below use single-quoted JSON and `\` line
> continuations, which work in **Git Bash, WSL and PowerShell 7+**. In
> **Command Prompt** neither does — put each command on one line and escape the
> inner double quotes with backslashes instead:
>
> ```
> curl -s -X POST http://localhost:8080/accounts -H "Content-Type: application/json" -d "{\"name\":\"Alice Checking\",\"currency\":\"USD\"}"
> ```
>
> The simplest fix is to run the demo from Git Bash, which ships with Git for
> Windows and takes these commands verbatim.

```bash
curl -s -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice Checking","currency":"USD"}'
```

```bash
curl -s -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -d '{"name":"Bob Checking","currency":"USD"}'
```

Take the `id` from each response, then move $10.25 from Alice to Bob:

```bash
curl -s -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-payment-1" \
  -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"10.25","currency":"USD","description":"invoice 42"}'
```

The response carries the transaction and both postings — a `CREDIT` of 1025 on
Alice and a `DEBIT` of 1025 on Bob. Then read the derived balances:

```bash
curl -s http://localhost:8080/accounts/<ALICE_ID>/balance
```

```bash
curl -s http://localhost:8080/accounts/<BOB_ID>/balance
```

Alice reads `balanceMinorUnits: -1025`, Bob reads `1025`.

### Rejection paths worth trying

A sub-cent amount is refused rather than rounded (**400**):

```bash
curl -s -X POST http://localhost:8080/payments -H "Content-Type: application/json" -H "Idempotency-Key: demo-subcent" -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"10.255","currency":"USD"}'
```

A currency the account is not denominated in is refused (**400**):

```bash
curl -s -X POST http://localhost:8080/payments -H "Content-Type: application/json" -H "Idempotency-Key: demo-wrong-currency" -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"5.00","currency":"EUR"}'
```

An unknown account is a **404**. After any of these, the balances are unchanged.

### Refunds

Capture the payment id and its transaction id from the `POST /payments`
response, then refund part of it:

```bash
curl -s -X POST http://localhost:8080/payments/<PAYMENT_ID>/refunds \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-refund-1" \
  -d '{"amount":"4.00","description":"partial refund"}'
```

The response reports `refundedTotalMinorUnits: 400` and
`remainingRefundableMinorUnits: 625`, and carries a **new** transaction whose
postings are the payment's reversed — a DEBIT back to the payer, a CREDIT off
the payee. Balances move to `-625` and `625`.

Asking for more than remains is refused with **422 `refund_amount_exceeded`**,
and nothing changes:

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/payments/<PAYMENT_ID>/refunds -H "Content-Type: application/json" -H "Idempotency-Key: demo-over-refund" -d '{"amount":"6.26"}'
```

### Reversals

A reversal takes no amount — it always negates the whole transaction. The body
is optional:

```bash
curl -s -X POST http://localhost:8080/transactions/<TRANSACTION_ID>/reversals \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-reversal-1" \
  -d '{"description":"reversing invoice 42"}'
```

Reversing the same transaction twice is refused with
**422 `transaction_already_reversed`**, naming the transaction that already did
it:

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/transactions/<TRANSACTION_ID>/reversals -H "Content-Type: application/json" -H "Idempotency-Key: demo-reversal-2" -d '{}'
```

To confirm the original was never edited — this prints `2|0`, two postings still
netting to zero:

```bash
docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT COUNT(*), COALESCE(SUM(CASE WHEN type='DEBIT' THEN amount_minor ELSE -amount_minor END),0) FROM postings WHERE transaction_id='<TRANSACTION_ID>';"
```

### Idempotency

Every write above carries an `Idempotency-Key`. Retry one verbatim — same key,
same body — and the original response comes back unchanged:

```bash
curl -s -D - -o /dev/null -X POST http://localhost:8080/payments -H "Content-Type: application/json" -H "Idempotency-Key: demo-payment-1" -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"10.25","currency":"USD","description":"invoice 42"}'
```

The response headers include `Idempotent-Replay: true`, and no second payment
exists. Reuse that key with a *different* body and it is refused with **409**:

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/payments -H "Content-Type: application/json" -H "Idempotency-Key: demo-payment-1" -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"99.99","currency":"USD"}'
```

Omit the header entirely and it is a **400**:

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/payments -H "Content-Type: application/json" -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"1.00","currency":"USD"}'
```

To see the concurrency guarantee, fire 100 at once with one shared key and count
what actually happened:

```bash
KEY="race-$(date +%s)"; for i in $(seq 1 100); do curl -s -o /dev/null -X POST http://localhost:8080/payments -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"12.34","currency":"USD"}' & done; wait; docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key='$KEY';"
```

That prints `1`.

### Ledger-wide check

Every posting ever written must net to zero:

```bash
docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT COALESCE(SUM(CASE WHEN type='DEBIT' THEN amount_minor ELSE -amount_minor END),0) FROM postings;"
```

---

## Schema

```
V1 ── accounts     (id, name, currency, created_at)
      transactions (id, description, currency, created_at)
      payments     (id, source_account_id, destination_account_id, transaction_id,
                    amount_minor, currency, status, created_at)
      postings     (id, transaction_id, account_id, type, amount_minor, currency, created_at)

V2 ── refunds      (id, payment_id, transaction_id UNIQUE,
                    amount_minor, currency, created_at)
      reversals    (id, original_transaction_id UNIQUE,
                    reversal_transaction_id UNIQUE, created_at)

V3 ── idempotency_keys (id, idempotency_key, endpoint, request_fingerprint,
                    response_status, response_body, created_at, expires_at,
                    UNIQUE (endpoint, idempotency_key))

V4 ── outbox_events    (id, aggregate_type, aggregate_id, event_type, topic,
                    payload, occurred_at, published_at, publish_attempts)
      processed_events (consumer_name, event_id, processed_at,
                    PRIMARY KEY (consumer_name, event_id))

V5 ── settlement_records         (id, external_id UNIQUE, external_reference,
                    amount_minor, currency, status, settled_at, created_at)
      reconciliation_runs        (id, started_at, completed_at, internal_examined,
                    external_examined, matched, discrepancies)
      reconciliation_incidents   (id, run_id FK, discrepancy_type, severity, status,
                    transaction_id, settlement_record_id, internal_amount_minor,
                    external_amount_minor, difference_minor, currency,
                    internal_status, external_status, detail, created_at, resolved_at)

V6 ── payments.status may now also be 'REVERSED' (widened CHECK constraint;
                    no new table, no new column, no backfill)

V7 ── two read indexes on payments (source_account_id, created_at) and
                    (destination_account_id, created_at) for the detection
                    layer. No table, no column, no write path
```

`settlement_records` is the one money-bearing table here that is **mutable**,
and deliberately so: it models a system we do not own. Everything under the
ledger stays append-only.

`postings.currency` is carried per posting rather than inherited from the
transaction, because the invariant is stated per currency and the balance
aggregate is per account *and* currency.

**Phase 2 added no columns to the Phase 1 tables.** The links live on the new
rows, so nothing that already worked was disturbed. Neither new table stores
money that moves — money still moves only through postings.

---

## Not in this phase

**The model is unvalidated, and nothing here pretends otherwise.** There is no
labelled data, so precision and recall are unmeasured. No scheduled retraining,
no persistence of the model across a restart, no drift detection and no
champion/challenger - the last two because without labels you can only detect
drift in the *input* distribution, never in accuracy, and comparing two models
needs a metric to compare them on.

Phase 10 explains those scores and validates neither. An explanation makes a
model's behaviour inspectable; it cannot make an unmeasured detector a measured
one, and a specific-sounding attribution of an unvalidated score is still an
unvalidated score. Phase 11 changes only how that explanation is worded, and if
anything raises the stakes: fluent prose is more persuasive than a template
without being more correct.

Phase 12 builds the machinery to measure all of it and still does not validate
the detector against reality, because no real fraud has passed through this
ledger. What it does establish is structural -- the composite ceiling -- and
structural findings need no representative sample. Refitting the weights and
thresholds against a real labelled population, with a held-out split, is the
phase this one argues for and deliberately refuses to be.

Property-based testing was the Phase 6 deliverable, ChaosLab the Phase 7 one, the
statistical signal layer the Phase 8 one, the Isolation Forest the Phase 9 one
the explanation layer the Phase 10 one, the LLM narratives the Phase 11 one, the
validation harness the Phase 12 one and the aggregation fix the Phase 13 one;
all eight now exist - see
[Verification](#verification-phase-6),
[Resilience](#resilience-phase-7---chaoslab),
[Detection](#detection-phase-8---statistical),
[Machine learning](#machine-learning-phase-9---isolation-forest),
[Explanation](#explanation-phase-10),
[LLM narratives](#llm-narratives-phase-11---groqcloud),
[Validation](#validation-phase-12---labels) and
[The composite ceiling fix](#the-composite-ceiling-fix-phase-13).

Each layer's coverage limits are deliberate and documented rather than implied.
ChaosLab does not exercise multi-instance network partitions, clock skew between
nodes, disk exhaustion or lock contention under sustained load. The statistical
layer uses no time decay and no per-account baseline for its two rate signals,
and every signal is per-account, so coordinated activity spread across many
accounts is invisible to all five. The ML layer inherits that per-account limit,
adds an imputation risk that grows as the population's history thins, and has
never been measured against ground truth. The explanation layer's feature
attribution is faithful to the paths a point took and is not a unique
decomposition of the score: correlated features take credit from each other, and
a feature no tree happened to split on earns nothing however telling its value.
The LLM layer validates that every name and number in a generated narrative is
grounded, and cannot validate that grounded parts were assembled into a truthful
whole.

Two pieces of deliberate debt, both documented where they live:

- Nothing sweeps expired idempotency keys. `expires_at` is written and never
  read, so `idempotency_keys` grows without bound.
- Nothing prunes published outbox rows either. `outbox_events` keeps every
  event forever, which is useful for auditing and unsustainable for storage.
- `reconciliation_runs` and `reconciliation_incidents` grow without bound too,
  and every run re-examines the entire ledger rather than a window since the
  last one. Fine at demo scale, wrong at real scale.

Both need a retention job, which is a phase of its own rather than something
to bolt on here.
