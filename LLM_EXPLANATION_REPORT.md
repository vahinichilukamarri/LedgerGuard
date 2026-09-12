# LLM Explanation Report — Phase 11 (GroqCloud)

**Model:** `llama-3.1-8b-instant`, configurable · **Evidence:** closed payload, no free text
**Validation:** five layers, pre-serve · **Fallback:** Phase 10 templates, logged not surfaced
**Tests:** 89 new (468 total), none live · **Validation of the scores themselves: still none**

Phase 10 generated narratives from templates: deterministic, ungrammatical in
places, and incapable of saying anything the numbers did not. This phase lets a
hosted model write them instead, and spends almost all of its code on making
sure that change cannot cost anything.

The sentence that governs the document: **better prose is not better ground
truth.** An LLM narrative describes the same unvalidated statistical and ML
outputs Phase 10 described. It reads more fluently. It knows nothing more.

---

## How to run it

From PowerShell, in the repository root. The tests need no key and make no
network calls:

```powershell
mvn test -D"test=NarrativeValidatorTest,NumericGroundingTest,GroqGatewayTest,HttpGroqClientTest,NarrativeCacheTest,NarrativeServiceTest,NarrativeEvidenceTest"
```

To run the layer for real, put a key in `.env` (which is gitignored) or set it
in the shell:

```powershell
$env:LEDGERGUARD_GROQ_API_KEY = "<your key>"
```

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>/explanation"
```

The deterministic narrative is always one query parameter away:

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>/explanation?narrative=template"
```

> Use `curl.exe`, not `curl` — in PowerShell `curl` is an alias for
> `Invoke-WebRequest`, which takes entirely different arguments.

With no key set, everything above works and returns `"narrativeSource":
"TEMPLATE"`. That is a supported way to run the system, not a broken one.

---

## Secrets

Stated first because it is the constraint with no acceptable failure mode.

- The key is read from `LEDGERGUARD_GROQ_API_KEY`, the same environment-variable
  pattern the database and Kafka credentials have used since Phase 1.
- `application.yml` contains the placeholder `${LEDGERGUARD_GROQ_API_KEY:}` and
  no value.
- `.env` is gitignored; `.env.example` is committed with an empty value and a
  pointer to the Groq console.
- The key is sent in an `Authorization` header and **never logged** — not on
  success, not in an exception message, not at debug. A test asserts that no
  failure message from any status code contains either the key or the word
  `Bearer`, because an error message ends up in a log and a key in a log is a
  leaked key.
- Verified before the first config commit that no key material was tracked
  anywhere in the repository.

---

## 1. The evidence payload

The model's entire universe is one record built from Phase 10's
`AccountExplanation` and from nothing else — no second pass over the ledger, no
extra query, no recomputation:

```json
{ "statistical": { "composite": 0.556, "applicableSignals": 2, "wellEvidenced": false,
    "signals": [ { "name": "amount_outlier", "applicable": true, "fired": true,
                   "score": 1.0, "contribution": 0.556,
                   "detail": "90000.00 USD is 12642.5 robust deviations above this account's usual 30.00 USD (30 prior payments, scale from median absolute deviation)" } ] },
  "model": { "score": 0.675, "trainingRows": 230,
    "drivers": [ { "feature": "amountModifiedZ", "share": 0.7, "percentile": 1.0, "direction": "above" } ] },
  "agreement": "BOTH_ELEVATED", "corroborated": true,
  "corroboratedSignals": ["amount_outlier"],
  "driversOutsideStatisticalView": ["log10LargestRecentAmount"] }
```

Three decisions inside it are worth stating.

**All five signals travel, including the ones that could not judge.** Phase 8's
three-state design only survives into the prose if the model is told which
signals were silent for want of data. A payload carrying the two that fired
would produce narratives describing the other three as quiet.

**Each signal's own sentence goes verbatim.** It is the richest grounded
material available — written by the code that did the arithmetic, with the real
amounts and sample sizes in it — and paraphrasing it here would put a third
wording of one fact into the system.

**Values are rounded to three decimals on the way in**, so the model sees the
digits the validator will accept, the cache key is stable against float noise
far below anything a sentence would mention, and no narrative quotes
`0.5558333`.

### No user-controlled text reaches the model

A property rather than luck, and it makes the prompt-injection surface close to
nothing. The payload carries enum names, numbers, and sentences this system
generated. There are no payment descriptions, no counterparty names, no account
labels — Phase 8 never collected any of them, because none of them were needed
to compute a signal. An attacker who controls payments controls, at most, some
doubles.

---

## 2. The prompt

The load-bearing line is the one that says what job the model has. Asked to
*explain why this account is anomalous*, a model reasons: it weighs, it infers
motive, and it reaches for "likely". Asked to *rewrite this record as prose,
adding nothing*, the same model does something much closer to what is wanted,
because the second framing leaves no room for a conclusion the record does not
contain.

Around that:

- **Rules as prohibitions**, numbered, with the forbidden vocabulary listed
  inline rather than merely enforced afterwards.
- **A worked example showing an evidence payload next to its narrative**, so
  the model can see that every number in the output came from the input.
  Showing the narrative alone would have taught the style and not the
  constraint. The example's values are deliberately unlike anything the system
  produces, so a bleed shows up as an ungrounded number rather than blending in.
- **Temperature 0**, capped `max_tokens`, plain text out.
- **A version string** that is part of the cache key, so editing the wording
  cannot serve old prose as if the new prompt had written it.

Every rule is checked again after generation. That is not redundancy: **a prompt
is a request and a validator is a guarantee**, and the difference shows up
precisely where a model improvises. The prompt exists to make rejection rare;
the validator exists to make it safe when the prompt fails.

---

## 3. Validation

Five layers, cheapest first, none short-circuiting.

| Layer | Rejects | Reason code |
|---|---|---|
| Structure | empty, under 80 or over 2000 chars, markdown, `Here is a summary:` | `EMPTY`, `TOO_SHORT`, `TOO_LONG`, `MARKUP`, `PREAMBLE` |
| Vocabulary | Phase 10's fifteen wrongdoing/certainty words | `FORBIDDEN_WORD` |
| Identifiers | a real signal or feature this account's evidence never carried | `UNGROUNDED_IDENTIFIER` |
| Identifiers | a token shaped like one of ours that exists nowhere | `FABRICATED_IDENTIFIER` |
| Numbers | any number that is not a truthful rendering of an evidence value | `UNGROUNDED_NUMBER` |
| State | prose contradicting the agreement state it was given | `STATE_CONTRADICTION` |

### Numbers: the hard one

Exact string matching cannot do this job. The evidence holds `0.556` and a good
sentence says "0.56", or "56% of the composite", or "0.6" — all truthful, and
only the first survives a string comparison. So **a stated number is grounded
when some evidence value, rounded to the precision the narrative chose to write,
equals it.** "0.56" grounds against 0.556; "0.58" grounds against nothing, which
is the case that matters.

Two deliberate loosenings:

- A value in `[0,1]` also grounds its hundredfold, so 0.70 licenses "70%" and
  0.99 licenses "the 99th percentile". A bare "70" meaning something else would
  pass. Accepted, because the alternative is parsing `%` and the word
  "percentile" out of free prose, which fails toward rejecting correct
  narratives — and a false rejection costs a worse summary on every good
  request, while this loosening costs a rare miss.
- Three **scale constants** are always groundable: 0.5 as the middle of the
  isolation distribution, and the two elevation conventions. They are facts
  about the scales rather than about the account, Phase 10's own templates quote
  all three, and rejecting them would fail correct prose for describing the
  scale it is using.

One detail took a second pass: identifiers are **masked out of the text before
numbers are extracted**. Otherwise `log10SecondsSinceLastPayment` contributes a
10 that no evidence explains, and every correct narrative naming the dormancy
feature is rejected for a digit inside a word.

### Identifiers: two failures that read identically

A fabricated name (`velocityZScore`) and a real name the evidence never carried
(`burstSurprisal`, when it was not a driver) mislead a reader in exactly the same
way. They are counted separately because they say different things about the
model: the first is invention, the second is substitution, and the mix decides
whether the answer is a better prompt or a different model.

### What validation cannot catch

Stated plainly, because the guarantee is narrower than it looks. This checks
that every **name** and every **number** is grounded and that the prose does not
contradict its state. It cannot catch grounded parts assembled into a misleading
whole — the right number attributed to the wrong signal, or a causal story the
evidence does not support. Judging that requires understanding the sentence, and
anything capable of understanding it would need validating in turn.

**That residue never reaches zero, and it is the main reason the template
fallback exists and the source discriminator is on every response.**

---

## 4. Fallback

On any failure, the reviewer gets Phase 10's narrative — complete, correct,
deterministic, the one this system served for an entire phase — and the response
says `"narrativeSource": "TEMPLATE"`.

The failure is **logged, never surfaced**:

- gateway declines (no key, breaker open, attempts exhausted) → `DEBUG` or
  `WARN` in the gateway, `unavailable` counter;
- validation rejects → `WARN` naming the account and every reason, `rejected`
  counter.

No error field, no error status, no banner. A reviewer looking at a flagged
account is doing real work, and an interruption about a model provider tells
them nothing they can act on.

`NarrativeSource` has two values and deliberately not three. There is no
`FALLBACK` or `LLM_FAILED`: a template served after a timeout is the same
artefact as one served because no key is configured, and naming the difference
on the wire would turn an ordinary degradation into something that looks like an
incident.

---

## 5. Resilience

| Control | Value | Why |
|---|---|---|
| Connect timeout | 1.5 s | it sits on a read endpoint |
| Read timeout | 4 s | a slow narrative is worth less than a fast template |
| Attempts | 2 | every retry is latency a human waits through to reach a template that was available immediately |
| Backoff | 200 ms fixed | exponential with jitter is for a worker draining a queue |
| Breaker | 3 consecutive failures, 60 s cooldown | a timeout bounds one request and does nothing about the hundredth |

Non-retryable failures (401, 403, 400) skip the retry entirely — trying a bad key
twice is a way of being slow about an answer you already have.

The breaker is the control that matters during an outage. Without it, a provider
being down means every request is four seconds slower before serving the
template it was always going to serve. With it, a sustained outage costs three
slow requests and then nothing.

**No new dependency.** `RestClient` and Jackson both arrive with
`spring-boot-starter-web`, which this project has had since Phase 1, and Groq's
API is OpenAI-shaped JSON over HTTPS. A vendor SDK would have added a
dependency, a second HTTP stack, and its own retry behaviour underneath the one
this layer is supposed to own — to save writing two request records.

---

## 6. Model choice

`llama-3.1-8b-instant`, as a configurable default.

The task is **constrained rewriting of a fixed record**, not reasoning. There is
no judgement to make, no evidence to weigh, nothing to infer — the hard thinking
was done by Phases 8 through 10, and what remains is turning a JSON object into
four sentences without adding anything. That is the task profile where the
cheapest and fastest model is the right starting point.

| Candidate | Why not the default |
|---|---|
| `llama-3.3-70b-versatile` | better instruction-following, roughly an order of magnitude more cost and noticeably more latency on a synchronous read. The upgrade path if rejection rates warrant it |
| `openai/gpt-oss-20b` | strong adherence for its size, but slower than the 8b for a task this constrained |
| Hardcoding any of them | hosted catalogues deprecate names and swap weights behind stable ids; a constant in a class would rot silently |

**The choice is falsifiable, which is the point.** `NarrativeService.stats()`
reports `rejectionRate()` — rejections over judged completions — and the
validator's reason codes say *why*. A high rate dominated by `UNGROUNDED_NUMBER`
argues for a bigger model; one dominated by `PREAMBLE` or `MARKUP` argues for a
better prompt. "The small model is good enough" is a measurement here, not an
opinion, and the number is available the moment the layer runs against real
traffic.

---

## 7. Determinism, stated honestly

**Phases 7 through 10 could promise that the same ledger produced the same
output. This phase cannot, and pretending otherwise would be the exact species of
claim this project spends its documentation avoiding.**

Temperature 0 is not determinism. Hosted inference varies with batching and
kernel scheduling, and providers update weights behind a stable model id. A
`temperature: 0.0` line in a config file buys a strong bias toward the same
answer and guarantees nothing.

So the decision is **both**, with the cache doing the real work:

- temperature 0, because the bias is free and real;
- an in-memory LRU cache keyed on `SHA-256(model | promptVersion | evidence
  JSON)`.

What that buys is narrower than determinism and is most of what determinism was
protecting: **the same evidence returns the same words for as long as the entry
lives.** A reviewer refreshing an account does not watch the explanation
subtly rewrite itself, and two people discussing one account read the same
sentences.

What is in the key, and what is pointedly not:

| In | Why |
|---|---|
| model id | a different model writes differently |
| prompt version | editing instructions must invalidate |
| evidence JSON | it is the content |

| Out | Why |
|---|---|
| `asOf` | the narrative describes numbers, not instants; two requests a second apart with identical numbers should not pay twice to differ |
| account id | same reason — the evidence already distinguishes accounts that differ |

The flip side is the property that matters: **any change to any number the
narrative could mention changes the hash**, so stale prose about moved numbers is
not representable. Cached narratives cannot drift from the scores beside them.

In memory, bounded, lost on restart — the same posture as Phase 9's model, for
the same reason: a persisted store of generated text is another thing that can
disagree with the ledger, and the cost of missing it is one API call.

---

## 8. Latency and cost

Everything before this phase was a read against Postgres. This is the first part
of the system that costs money per invocation and can be slow for reasons
outside the process.

**The model runs on `GET /detection/accounts/{id}/explanation` and nowhere
else.**

| Endpoint | Narrative | Why |
|---|---|---|
| `/detection/anomalies` | template | N rows would mean N model calls to help someone choose which single account to open |
| `/detection/accounts/{id}` | template | a score lookup should stay a fast read |
| `/detection/accounts/{id}/explanation` | model, cached | a human has already chosen to look here |

The alternatives, and why not:

- **Synchronous everywhere** — simplest, and turns a fifty-row ranking into
  fifty calls and several seconds. The ranking is the page people load most and
  read least.
- **Pre-generation on a schedule** — fast reads, but narratives go stale against
  a moving ledger (the cache key makes staleness impossible *within* a
  narrative, but a pre-generated one for evidence that has since changed is
  simply never used), and you pay for accounts nobody opens.
- **On demand with a cache, on one endpoint** — chosen. Spend scales with human
  attention rather than with page size, the first look costs one call of a few
  hundred milliseconds, and every look after that is free until the numbers
  move.

Worst case for a reviewer is bounded and known: connect 1.5 s plus read 4 s, two
attempts, and then a template. In practice a breaker trip makes a sustained
outage cost nothing after the third request.

---

## 9. API surface

No writes, no new endpoint.

| Field | Change |
|---|---|
| `summary` | may now be model-written |
| `narrativeSource` | **new** — `TEMPLATE` or `LLM` |
| `?narrative=template` | **new** — skip the model, take Phase 10's wording |

The discriminator is not decoration. The template is a function of the numbers
and cannot say anything they do not, because a person wrote it while looking at
them. The model's version is validated against those numbers but was not derived
from them, and validation catches grounded-parts-assembled-wrongly less reliably
than it catches ungrounded parts. A reviewer who cannot tell which one they are
reading has to apply the weaker standard to both.

`?narrative=template` exists for the audit trail, for regression tests, and for
the moment when a reviewer reads a sentence that sounds off and wants to see
what the deterministic layer said.

---

## 10. Findings during implementation

### FINDING-1 — an absolute epsilon says every small number is every other one

`NumericGrounding` compared stated and evidence values with an absolute
tolerance of `1e-9`. A test asked whether `2.41e-14` grounds against
`2.41e-13`; they differ by `2.17e-13`, which is comfortably under that
tolerance, so the validator called them equal.

This was not hypothetical. Phase 8's reconciliation-mismatch signal writes
p-values at exactly that magnitude into its own sentence, which is evidence the
narrative is encouraged to quote — so the validator would have accepted a
narrative that moved a p-value by an order of magnitude. Now relative:
`|a−b| ≤ 1e-9 · max(|a|,|b|)`.

### FINDING-2 — two constructors and no marker

`NarrativeService` had a production constructor and a test constructor, both
public, neither annotated. Spring refused to guess and the entire application
context failed to start — not the LLM layer, the whole application. Caught by the
integration test, which is where a wiring failure should be caught rather than in
a deployment. The production constructor is now `@Autowired` and the other is
documented as the test seam.

Both findings came from writing the tests rather than from reading the code,
which is the third phase running where that has been true.

---

## 11. What this phase deliberately does not do

- **No change to any number.** The statistical signals, the Isolation Forest,
  the attribution and the agreement states are exactly as Phases 8 through 10
  computed them. Only the sentence around them is different.
- **No LLM in the detection path.** The model narrates; it does not score,
  weight, rank, or decide.
- **No prompt-tuning loop, no eval harness.** The rejection rate is the
  measurement, and there is no labelled corpus of good narratives to tune
  against.
- **No streaming, no async, no queue.** One synchronous call on one endpoint.
- **No persistence of narratives.** Recomputed or re-cached, never stored.

---

## 12. The standing caveat, restated

Everything Phase 10's report said is still true, and this phase adds one more
thing to be careful about.

- Neither score is validated. There is no labelled data in this system, so
  precision and recall remain **unmeasured rather than approximately known**.
- The statistical weights are unfitted judgement; the elevation thresholds are
  conventions; the feature attribution is one credit rule's reconstruction.
- **A narrative is a rendering of those numbers, and a better rendering of an
  unvalidated number is still an unvalidated number.** Fluent prose is more
  persuasive than a template without being more correct, which means the
  upgrade in this phase carries a small, genuine risk: it makes the output
  *easier to believe* without making it *more believable*.

That asymmetry is why the validator is strict, why the source is on every
response, why the template is one query parameter away, and why the forbidden
vocabulary is enforced twice.

---

## Posture heading into Phase 12

The system can now explain itself in two layers, in readable prose, with every
name and number in that prose checked against the evidence that produced it, and
with a deterministic version always available for comparison.

It still cannot tell you whether any of it is right. Every road out of that
still leads through labels — a reviewer marking outcomes, a chargeback feed,
anything that turns "this account deviates from X in ways Y" into "and here is
what happened next". A model that writes well about unvalidated scores has not
moved that problem an inch; it has only made the scores pleasanter to read.
