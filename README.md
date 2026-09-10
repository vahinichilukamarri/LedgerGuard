# LedgerGuard

A payment integrity platform, built in locked phases.

**Current phase: Phase 5 — Settlement Simulator + Reconciliation.** The ledger
is now checked against an independent external record, because being
self-consistent and being correct are not the same thing.

| Phase | Tag | What it added |
|---|---|---|
| 1 — Ledger Core | `v0.1-ledger-core` | Double-entry accounts, payments, transactions and immutable postings, with balances derived from the postings ledger. |
| 2 — Refunds, Reversals & Transaction Safety | `v0.2-transaction-safety` | Full and partial refunds, single-use reversals, and proven all-or-nothing writes. Both are new transactions, never edits. |
| 3 — Idempotency & Safe Retries | `v0.3-idempotency` | Required idempotency keys, byte-identical replay, and exactly-one-effect under concurrent duplicates. |
| 4 — Transactional Outbox + Kafka | `v0.4-kafka-outbox` | Events written with the ledger transaction, published after commit, at-least-once with consumer-side deduplication. |
| 5 — Settlement Simulator & Reconciliation | `v0.5-reconciliation` | An independent external settlement source, six discrepancy classifications, and persisted incidents with evidence linkage. |

Nothing beyond those five phases is implemented.

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
`reconciliation_incidents`. All of them arrive with their foreign keys,
not-null and check constraints.

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

104 tests across twelve classes:

| Class | Tests | Covers |
|---|---|---|
| `TransactionServiceBalanceTest` | 11 | the Σ debits = Σ credits invariant, no database |
| `PostingImmutabilityTest` | 7 | no mutation path onto a posting exists |
| `PaymentFlowIntegrationTest` | 6 | payment → balance, end to end |
| `RefundServiceTest` | 6 | the refund cap and posting direction |
| `ReversalServiceTest` | 5 | reverse-once, and exactness of the negation |
| `RefundReversalFlowIntegrationTest` | 6 | refund/reversal flows plus the atomicity proof |
| `RequestFingerprintTest` | 10 | key-order independence, and what must change the fingerprint |
| `IdempotencyFlowIntegrationTest` | 10 | replay, conflict, scoping, and the concurrency race |
| `OutboxRecorderTest` | 7 | envelope shape, stable event ids, integer amounts on the wire |
| `OutboxKafkaFlowIntegrationTest` | 6 | publish, consume once, redelivery, and outbox atomicity |
| `ReconcilerTest` | 21 | all six classifications, their edge cases, and the severity scheme |
| `ReconciliationFlowIntegrationTest` | 9 | each discrepancy injected end to end, evidence linkage, and run-to-run dedupe |

The integration tests start their own throwaway PostgreSQL via Testcontainers
and run the real Flyway migrations against it — no in-memory database stand-in,
because the schema constraints are part of the product.

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

Property-based testing, ChaosLab and anything ML-shaped are out of scope for
Phase 5 and are not implemented.

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
