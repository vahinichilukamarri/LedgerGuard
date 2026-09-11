# ChaosLab Report — Phase 7

**Run date:** 2026-09-11 · **Result:** 15 scenarios, 15 passed, 0 failed
**Defects found:** 1 (documentation) · **Functional defects:** none

ChaosLab injects controlled faults into the transaction pipeline and asserts that
the ledger's correctness invariants survive them. Phase 6 proved the invariants
hold across randomized *inputs*; this phase asks whether they hold across
randomized *failures*, which is a different question and a harder one.

---

## How to run it

All scenarios are ordinary JUnit tests. From PowerShell, in the repository root:

```powershell
mvn test -D"test=*ChaosTest"
```

The harness self-test, which proves the faults are real rather than inert:

```powershell
mvn test -D"test=ChaosHarnessTest"
```

One area at a time, when a scenario fails and you want its output uncluttered:

```powershell
mvn test -D"test=OutboxChaosTest"
```

> **PowerShell note:** quote the `-D` argument as `-D"test=..."`. Unquoted, the
> `*` is expanded by PowerShell before Maven sees it, and `=` inside an unquoted
> `-D` is parsed as an operator. The quoted form above works in both PowerShell
> and `cmd.exe`; in Git Bash, `-Dtest='*ChaosTest'` is equivalent.

Docker must be running — the scenarios use a real PostgreSQL via Testcontainers.

---

## Design in one paragraph

The harness is in-process and adds **no production code**. Every fault is
injected at a seam that already existed: `ChaosDataSource` wraps the pooled
`DataSource` through a `BeanPostProcessor` and fails a nominated JDBC statement;
`ChaosKafkaTemplate` replaces the `KafkaTemplate` that `OutboxPublisher` is
constructed with; `TickingClock` replaces the injected `Clock`. Nothing is
mocked at the repository level, because a mocked repository failure never
involves the database and therefore proves nothing about rollback. Randomness
comes only from `Chaos`, seeded with a constant the scenario declares, so a
failure reproduces exactly.

One deliberate exception to "reuse, don't rebuild": external settlement faults
use the `SettlementFaultService` that Phase 5 already ships, rather than a
parallel mechanism.

### Why the broker is a fake

The scenarios test *our* handling of the broker contract, not the broker. The
most important failure in that contract — **the broker accepted the record and
the acknowledgement was lost** — is precisely the one a real broker will not
perform on demand, and it is the one that produces the duplicate the whole
outbox design exists to tolerate. Phase 4's `OutboxKafkaFlowIntegrationTest`
keeps the real-broker proof; ChaosLab reaches the states that proof cannot
stage.

---

## Scenarios

### Outbox and the dual-write gap — `OutboxChaosTest`

| # | Scenario | What it attacks | Invariant asserted | Result |
|---|---|---|---|---|
| S1 | Broker unreachable during publish | The assumption that publishing is part of paying — if it were, a broker outage would be a payments outage | Ledger commits; event waits unpublished; publishes itself when the broker returns, with no intervention | **PASS** |
| S2 | Acknowledgement lost after the broker accepted | Treating a send failure as "it did not happen" | Row stays unpublished; event is republished; both copies byte-identical; consumer absorbs the duplicate — **2 deliveries, 1 effect** | **PASS** |
| S3 | Database refuses the UPDATE that marks a batch published (crash between send and mark) | The exact gap `OutboxPublisher` documents | Whole batch republished; **6 deliveries of 3 events produce exactly 3 effects**; nothing lost | **PASS** — and surfaced [DEFECT-1](#defect-1) |
| S3b | Outbox INSERT fails during a payment (**the dual-write gap itself**) | Money moving without the announcement being recorded | The payment **refuses to commit alone**: no payment, no transaction, no postings, no event. Retry is clean | **PASS** |
| S4 | One refused send inside a batch of five (failing position chosen by seed `20260911`) | Batch handling | Events before it publish; the batch stops; recovery publishes exactly the remainder; **no duplicates**, because a refused send never reached the broker | **PASS** |

The contrast between S3 and S4 is the point: a failure *after* the broker
accepted costs duplicates, a failure *before* costs none. Both cost nothing in
lost events.

### Database faults — `DatabaseFaultChaosTest`

Faults are injected at the JDBC statement, so the rollback under test is
PostgreSQL's own. Each asserts with a **full-database snapshot** — every table
the ledger owns — because "the payment is absent" would pass while an orphaned
posting or a stray outbox row sat there unnoticed.

| # | Scenario | What it attacks | Invariant asserted | Result |
|---|---|---|---|---|
| S5 | Connection dropped mid-payment, on `insert into postings` | The moment between the transaction row and the postings that balance it — a gap the Phase 1 invariant cannot see, since a transaction with no postings has nothing to be unbalanced about | Nothing written at all; balances unmoved; the retry afterwards succeeds cleanly | **PASS** |
| S6 | Connection dropped mid-refund, on `insert into refunds` | The refund cap, which is derived by summing refund rows | Nothing written; the refundable amount the failed refund would have consumed is still available and still usable | **PASS** |
| S7 | Statement timeout mid-reconciliation, on `insert into reconciliation_incidents` | A run row claiming a clean ledger it never finished examining | No run row, no partial incidents; re-running after recovery finds the discrepancy the failed run would have reported | **PASS** |
| S7b | Connection held broken across every INSERT | The optimistic case in S5, where a one-shot fault lets the retry straight through | Nothing written; no statement slips past inside the same transaction | **PASS** |

### Delivery shape — `DeliveryChaosTest`

Both consumers are driven together, because they deduplicate by different
mechanisms — `LedgerEventConsumer` claims a row in `processed_events`,
`SettlementSimulator` derives a unique external id from the event id — and
testing one says nothing about the other.

| # | Scenario | What it attacks | Invariant asserted | Result |
|---|---|---|---|---|
| S8 | One event delivered eight times to both consumers | The assumption that redelivery is rare enough to ignore | One claim and one settlement record, checked after *every* delivery. A second settlement record would later surface as a DUPLICATE_SETTLEMENT incident blaming the ledger for a consumer bug | **PASS** |
| S9 | A refund settling **before** its own payment | Any hidden assumption that a refund is processed after the payment it refunds | Both settle for their own amounts; order is irrelevant because each event names the transaction it settles | **PASS** |
| S10 | Six aggregates, 1–3 copies each, shuffled (seed `70701`) | Deduplication that is really "ignore what I saw last" — duplicates here are separated by other events | Exactly one effect per aggregate; every payment settled for its own amount, so nothing was suppressed by a neighbour's duplicate or misattributed | **PASS** |

### Reconciliation partial failures — `ReconciliationChaosTest`

Reconciliation is the last line of defence, which makes its own failure modes the
most dangerous in the system. A pass that throws stops checking everything after
it; a pass that silently skips a record has certified a ledger it never examined.
The asserted invariant is therefore **nothing is silently dropped**, not "the
right incident type was chosen" — `ReconcilerTest` owns the latter.

| # | Scenario | What it attacks | Invariant asserted | Result |
|---|---|---|---|---|
| S11 | Four unmatchable external records: null reference, blank reference, a reference that is not a UUID, and a reference to an unknown transaction | The matching key | The run completes; **all four appear in incidents**; the one correctly settled transaction still reconciles cleanly | **PASS** |
| S12 | External feed truncated — two of three transactions settled | The difference between "not settled yet" and "never settled" | Inside the grace window: no incident, one counted as awaiting. Past it: exactly one MISSING_SETTLEMENT, naming the transaction the feed actually dropped | **PASS** |
| S13 | An event reaching the processor with `amountMinor` removed, still valid JSON | The processor's parsing, then reconciliation's ability to notice what it made of it | The processor does not blow up; the resulting disagreement is **reported** as AMOUNT_MISMATCH with the ledger's own figure intact, not absorbed | **PASS** |

### Harness self-test — `ChaosHarnessTest` (7 tests)

Not a scenario, and not paranoia. Every scenario above proves something by
surviving an injected fault, and **all of them would pass, falsely, if the fault
were never injected** — a `BeanPostProcessor` that ran too late to wrap the
DataSource, or a `@Primary` that lost to the autoconfigured `KafkaTemplate`,
yields a suite that is green because nothing ever broke. A chaos suite that
cannot fail is worth less than none, because it is believed.

So the harness asserts on itself: armed faults really do throw through queries
the application makes, they are targeted and one-shot, disarming works, the
broker's three outcomes are genuinely distinct, the publisher is wired to the
fake broker, the application reads the scenario's clock, and each scenario
starts from an empty ledger.

---

## Defects found

### DEFECT-1

**A javadoc claim about batch publishing that was true only on one of two paths**

Classification: **documentation**. No functional defect — the behaviour it
described inaccurately is safe.

**Found by** `OutboxChaosTest` S3.

**What it claimed.** `OutboxPublisher.drainOnce()` documented:

> On a send failure the batch stops rather than aborting: events already
> confirmed by the broker keep their `published_at`, and the one that failed is
> retried next cycle.

**What actually happens.** That holds when a *send* fails: the loop breaks, the
transaction still commits, and the marks already applied survive. But marks are
applied by Hibernate at commit and the whole drain is a single transaction, so
when the *transaction* fails — the database refusing the UPDATE, or the process
dying before commit — **every mark in the batch rolls back**, including those for
events the broker already accepted. S3 measured it: three events, all three sent,
the mark UPDATE refused, and all three rows still unpublished. The next cycle
republished all three, for six deliveries of three events.

**Why it is not a functional bug.** Consumers deduplicate on `eventId`, and S3
confirms the six deliveries produced exactly three effects. Nothing is lost and
nothing is double-counted. The cost is a batch-sized burst of duplicates instead
of the single duplicate a send failure costs — a performance and noise
characteristic, not a correctness one.

**Why it still mattered.** The sentence read as an unconditional guarantee about
batch behaviour. Anyone tuning `batch-size`, or reasoning about duplicate volume
during a database blip, would have drawn the wrong conclusion from it — and the
larger the batch, the larger the burst it failed to mention.

**The fix.** The javadoc now distinguishes the two failure paths explicitly,
states that a transaction failure republishes the whole batch, notes that this is
safe because consumers deduplicate, and points out that `batch-size` bounds how
large the burst can be. Both paths share the property that actually matters, and
it is now said plainly: *nothing is ever marked published that the broker did not
confirm, so no event can be lost.*

**The regression.** `OutboxChaosTest` S3 is itself the regression test — it pins
the measured behaviour (3 events → 6 deliveries → 3 effects) so that a future
change to the transaction boundary cannot quietly alter it without the javadoc
being revisited.

---

## Functional defects: none found

After fifteen scenarios across four failure families, **no functional defect was
found in Phases 1–5**. The ledger's invariants held under every fault injected:
no double-spend, no lost transaction, no orphaned outbox entry, no ledger that
failed to net to zero, no event acted on twice.

That is stated plainly rather than dressed up, and it is worth being precise
about what it does and does not mean.

**What it means.** The transactional outbox does close the dual-write gap it
claims to close — S3b demonstrates a payment refusing to commit when its event
cannot be recorded, which is the structural property the pattern is chosen for.
Consumer-side deduplication genuinely absorbs the duplicates that at-least-once
delivery produces, including duplicates separated by other traffic. And
PostgreSQL's rollback, exercised through real dropped connections rather than
mocked repositories, leaves nothing behind.

**What it does not mean.** Fifteen scenarios are fifteen points in a large space.
They cover the failures this system's design makes most likely and most costly;
they do not cover network partitions between two application instances, clock
skew across nodes, disk-full conditions, or long-running transactions holding
locks under load. Those are absent because the scenarios were chosen for what the
outbox and reconciliation layers are actually wired to do, not because they were
tried and found harmless.

**The honest summary.** Phase 6 found a real defect because randomized inputs
reach combinations nobody thought to write down. Phase 7 found a documentation
error and no functional defect, which is a weaker result — and manufacturing a
bug to make the phase look more productive would have been worse than reporting
it.

---

## Resilience posture after Phase 7

| Failure | Behaviour | Verified by |
|---|---|---|
| Broker unavailable | Ledger unaffected; events queue and self-publish on recovery | S1 |
| Acknowledgement lost | Duplicate delivery, one effect | S2 |
| Crash between send and mark | Whole batch republished, one effect each | S3 |
| Crash between ledger write and outbox write | Payment rolls back entirely; the gap is closed structurally | S3b |
| Partial batch send failure | Clean stop, no duplicates, exact resumption | S4 |
| Database connection lost mid-write | Full rollback, nothing left behind, retry succeeds | S5, S6, S7b |
| Database failure mid-reconciliation | No partial run record, no half-written findings | S7 |
| Duplicate delivery | Absorbed by both consumers independently | S8, S10 |
| Out-of-order delivery | Irrelevant to settlement correctness | S9 |
| Malformed external data | Reported, never silently dropped | S11, S13 |
| Incomplete external feed | Exactly the missing transactions reported, after the grace window | S12 |

**Known gaps, stated rather than implied:** multi-instance network partitions,
clock skew between nodes, disk exhaustion, and lock contention under sustained
load are not covered. Nor is the real-broker path, which remains Phase 4's
`OutboxKafkaFlowIntegrationTest` rather than being duplicated here under a
noisier harness.
