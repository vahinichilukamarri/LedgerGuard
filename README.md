# LedgerGuard

A payment integrity platform, built in locked phases.

**Current phase: Phase 1 — Ledger Core.** Double-entry accounts, payments,
transactions and immutable postings, with balances derived from the postings
ledger. Nothing beyond that scope is implemented yet.

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

## Running it locally

### Prerequisites

- **JDK 21**
- **Maven 3.9+**
- **Docker** (for the local Postgres, and for the integration test)

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

That runs `postgres:16-alpine` on **localhost:5432** with database `ledgerguard`,
user `ledgerguard`, password `ledgerguard` — matching the defaults in
`application.yml`.

To point at a different database instead, override the environment variables:

| Variable | Default |
|---|---|
| `LEDGERGUARD_DB_URL` | `jdbc:postgresql://localhost:5432/ledgerguard` |
| `LEDGERGUARD_DB_USER` | `ledgerguard` |
| `LEDGERGUARD_DB_PASSWORD` | `ledgerguard` |
| `LEDGERGUARD_PORT` | `8080` |

### 2. Migrations

**Nothing to run by hand.** Flyway applies `db/migration/V1__init.sql`
automatically at startup, creating `accounts`, `transactions`, `payments` and
`postings` with their foreign keys, not-null and check constraints.

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

23 tests across three classes. `PaymentFlowIntegrationTest` starts its own
throwaway PostgreSQL via Testcontainers and runs the real Flyway migrations
against it — no in-memory database stand-in, because the schema constraints are
part of the product.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/accounts` | Create an account. |
| `GET` | `/accounts/{id}/balance` | Balance derived from postings. |
| `POST` | `/payments` | Payment → transaction → balanced DEBIT/CREDIT pair. Returns the transaction with its postings. |

`POST /accounts` is not itself a Phase 1 deliverable; it exists because the
payment flow needs two accounts to exist before it can be exercised at all.

### Error responses

| Status | When |
|---|---|
| `400` | Unknown currency, amount finer than the currency minor unit, currency mismatch against the account, failed field validation. |
| `400` | Body that is not parseable JSON — `error: "malformed_request_body"`, with the parser detail in `details`. Usually a shell quoting mistake; see the note in the demo section. |
| `404` | Unknown account. |
| `422` | Postings do not balance. |

Every error uses the same shape — `error`, `message`, `details`, `timestamp` —
including unparseable bodies, which would otherwise fall through to the
framework default shape and come back looking nothing like the rest of the API.

`POST /payments` cannot itself produce a 422, because it always constructs an
equal-and-opposite pair. The 422 guards `TransactionService` against *any*
caller — including whatever Phase 2 adds — and is covered by
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
curl -s -X POST http://localhost:8080/payments -H "Content-Type: application/json" -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"10.255","currency":"USD"}'
```

A currency the account is not denominated in is refused (**400**):

```bash
curl -s -X POST http://localhost:8080/payments -H "Content-Type: application/json" -d '{"sourceAccountId":"<ALICE_ID>","destinationAccountId":"<BOB_ID>","amount":"5.00","currency":"EUR"}'
```

An unknown account is a **404**. After any of these, the balances are unchanged.

### Ledger-wide check

Every posting ever written must net to zero:

```bash
docker exec ledgerguard-postgres psql -U ledgerguard -d ledgerguard -tAc "SELECT COALESCE(SUM(CASE WHEN type='DEBIT' THEN amount_minor ELSE -amount_minor END),0) FROM postings;"
```

---

## Schema

```
accounts     (id, name, currency, created_at)
transactions (id, description, currency, created_at)
payments     (id, source_account_id, destination_account_id, transaction_id,
              amount_minor, currency, status, created_at)
postings     (id, transaction_id, account_id, type, amount_minor, currency, created_at)
```

`postings.currency` is carried per posting rather than inherited from the
transaction, because the invariant is stated per currency and the balance
aggregate is per account *and* currency.

---

## Not in this phase

Refunds, reversals, idempotency handling, and Kafka are explicitly out of scope
for Phase 1 and are not implemented.
