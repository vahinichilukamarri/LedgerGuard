# Validation Report — Phase 12 (Labels)

**Label sources:** dispute feed, human review, fenced synthetic
**Anti-bias:** stratified sampling, blind review, leakage-safe joins
**Tuning:** none · **Tests:** 58 new (526 total)

Every report from Phase 8 onward ended with the same sentence: these scores are
unvalidated, because there is no labelled data. This phase builds the machinery
that ends that sentence, runs it, and reports what it found — including the
finding that matters most, which is about the detector rather than about the
labels.

Two things this report is not. It is not a validated detector: the only
population large enough to measure against is one this system generated, and
measuring a detector against anomalies designed by someone who knew what it
looks for is circular. And it is not a tuning pass: nothing here changed a
weight or a threshold, deliberately, because fitting against a first small label
set produces parameters that describe the label set.

---

## The headline finding

> **Superseded in Phase 13.** The ceiling described below is real and was closed in Phase 13. The recall figures quoted later in this report, however, were measuring two defects in this phase's own benchmark rather than the ceiling: the anomalies aged out of both the recent and the baseline windows before anything was scored. Corrected, the benchmark gives a statistical recall of 1.000 under *both* aggregations. See [COMPOSITE_CEILING_FIX_REPORT.md](COMPOSITE_CEILING_FIX_REPORT.md).


**Three of the five statistical signals must fire at saturation before a
fully-measured account is elevated at all.**

This is arithmetic, not a measurement that might have gone another way. The
composite is a weighted mean over the *applicable* signals, so one saturated
signal contributes exactly its renormalised weight:

| Signals firing at 1.00 | Composite (all five applicable) | Elevated at 0.50? |
|---|---|---|
| `amount_outlier` alone (the heaviest, 0.25) | **0.25** | no |
| the two heaviest (0.25 + 0.20) | **0.45** | no |
| the three lightest (0.20 + 0.20 + 0.15) | **0.55** | yes |

So an account whose largest payment is *twelve thousand robust deviations* from
its own history scores 0.25 and is not flagged. It needs two more signals to
agree with it.

### The mirror image of a documented trade

Phase 8 documented the renormalisation and framed it as protection for
thin-history accounts — "an account with only two measurable signals could never
exceed 0.45 however extreme its behaviour". The consequence in the other
direction was never written down:

- a **thin** account, where two signals apply, needs **one** to fire to clear 0.50;
- a **fully-measured** account, where five apply, needs **three**.

**The composite is easier to trip with less evidence, not harder.** That is the
opposite of what anyone designing it would want, and it went unnoticed across
four phases because nothing had ever measured recall.

Phase 10 met the same arithmetic from the other side and called it dilution — a
signal firing at full strength while the composite stayed quiet, which made the
narrative layer say the statistical layer had not seen something it had seen
perfectly well. That was treated as a presentation problem. It is a detection
problem, and now it has a number on it.

`CompositeCeilingTest` pins all of this, so a later phase that moves the weights
has to confront it deliberately.

---

## How to run it

From PowerShell, in the repository root. The fast tests need no Docker:

```powershell
mvn test -D"test=WilsonTest,ConfusionMatrixTest,PrecisionRecallCurveTest,AccountLabelTest,CompositeCeilingTest"
```

The integration tests and the synthetic benchmark need a container:

```powershell
mvn test -D"test=ValidationFlowIntegrationTest"
```

Against a running instance — draw an account to review, blind by default:

```powershell
curl.exe -s "http://localhost:8080/validation/review/next?stratum=AUDIT"
```

```powershell
curl.exe -s -X POST "http://localhost:8080/validation/labels" -H "Content-Type: application/json" -d '{\"accountId\":\"<ID>\",\"verdict\":\"BENIGN\",\"reviewer\":\"alex\",\"stratum\":\"AUDIT\",\"scoresVisible\":false}'
```

```powershell
curl.exe -s "http://localhost:8080/validation/report"
```

> Use `curl.exe`, not `curl` — in PowerShell `curl` is an alias for
> `Invoke-WebRequest`, which takes entirely different arguments.

---

## 1. Where labels come from

Three sources, never pooled by accident. Each is a different epistemic object
and `LabelSource` keeps them apart in the schema, in the evaluator, and on the
wire.

### The dispute feed

A chargeback simulator on the Phase 5 pattern: it consumes Kafka, applies its
own rules in its own table, and has no access to `payments`, `postings`, or any
score. That independence is the entire point — a label source derived from the
data being judged is a mirror, and a detector evaluated against a mirror passes
by construction.

Two properties it inherits from real chargebacks:

**It is late.** A dispute is written the moment the payment is seen and dated
30–90 days out, because that is when a cardholder notices. Nothing becomes a
label until that date arrives. The system therefore holds, at any moment,
disputes that *will* be raised and are not yet knowable — which is exactly the
position a real fraud team is in, and exactly what an evaluation must not peek
at.

**Not every chargeback is a fraud label.** A cardholder who never received goods
has a dispute with a merchant; one billed twice has a dispute with a processor.
Both are recorded because their rate is worth knowing, and only `FRAUDULENT`
becomes a label. Treating them alike is the commonest way one of these label
sets is poisoned, and it poisons in the worst direction: the contaminating cases
are disproportionately ordinary accounts having ordinary commercial arguments.

### What a positives-only source can and cannot measure

This runs opposite to the intuition, and the first draft of the code got it
backwards. With positives and no negatives:

- **Recall is estimable.** The set of known-anomalous accounts is known, so "how
  many did the detector flag" is a real calculation — over the accounts disputes
  identify, which is not all fraud, but is a population.
- **Precision is not.** It needs every flagged account classified, and a flagged
  account with no chargeback is unresolvable: false positive, or fraud nobody
  disputed, and nothing distinguishes them.

So precision comes from human review of the flagged stratum, and the dispute
feed supplies an independent recall estimate that no amount of reviewing the
detector's own output could produce. Complementary, not redundant.

### Human review

The first write path in a detection layer that has been read-only since Phase 8.
It writes an opinion, never a score — nothing here can change what the detector
computes, only what is recorded about whether it was right.

Append-only, like postings. A reviewer who revises writes a second label and the
first survives, because disagreement is measurable only if both verdicts exist.

### Synthetic — and why it has no endpoint

Generated with known ground truth, useful for exercising the harness, circular
as evidence. There is **deliberately no API that can write a synthetic label**
into a running system; generation is test-only, which is a stronger fence than
any amount of documentation. The enum value exists so the evaluator can be asked
to include them and reply with a warning saying the result is circular.

---

## 2. The four ways this measurement goes wrong

Each of these is designed against rather than noted.

### Verification bias — the recall denominator

Left alone, reviewers label what the detector shows them. That produces a label
set in which **every account the detector missed is invisible**, so false
negatives cannot be counted and the "recall" that falls out is 1.0 by
construction — precision wearing recall's name.

So the review queue draws from two pools: `FLAGGED`, dense in positives and
biased by construction, and `AUDIT`, a random sample of accounts the detector
did *not* surface. The audit draw is seeded (reproducible) and independent of
every score (the ordering carries no information about how interesting the
detector finds an account).

**This cannot be retrofitted.** A label set gathered without an audit stratum can
never have one grafted on, because the accounts that would have been sampled are
no longer a random sample of anything.

The evaluator enforces it: with zero audit labels, recall is reported as
unmeasurable and the field is omitted rather than filled with a number.

### Temporal leakage

A chargeback raised in November is evidence about behaviour in September. Every
account is scored **as of `labelledAsOf`**, never as of now. Scoring with
today's ledger would hand the detector every consequence of the fraud — the
refunds it caused, the reconciliation incidents it raised — and produce an
excellent number that nothing in production could reproduce.

The integration test that pins this is the most important in the phase: an
account pays quietly for forty days, is labelled benign, then sends eight
payments of 90,000 in sixteen seconds. The evaluation must still see the
day-forty score.

There is a residual leak the report warns about rather than fixes: the Isolation
Forest is trained at one instant and may have seen data from after the labelled
period. The evaluator counts affected accounts and says so.

### Anchoring

A reviewer shown "0.87" before judging produces an opinion about the detector's
opinion. The review queue is **blind by default** — seeing the scores is what a
caller asks for, not what they opt out of — and every label records whether they
were visible, so anchored and blind labels are reported apart.

The limit, stated rather than glossed: the queue serves a payload without scores
and cannot stop a reviewer opening the assessment endpoint in another tab.
`scoresVisible` is an assertion by the caller, not an enforced fact.

### Inter-reviewer disagreement is the ceiling

If two careful people disagree on a fifth of accounts, the labels are not ground
truth — they are one sample from a distribution of opinions — and no detector is
meaningfully "95% accurate" against them. Most systems never measure this,
because a second review of an already-labelled account buys no new coverage. It
is measured here, and an unmeasured agreement rate is reported as **absent
rather than perfect**.

---

## 3. Weighting, and the confidence intervals

The strata are sampled at wildly different rates, so raw counts describe a
population that does not exist. Each labelled account is weighted by
`stratum population / stratum labelled` — the design-based estimator — so one
audit account reviewed out of two hundred unflagged speaks for all two hundred.
Dispute labels are never weighted: a scheme raised them or did not, and
inventing a weight would dress an unknown sampling process as a known one.

Intervals come from the **unweighted** counts, because weights change what is
being estimated and not how much evidence there is for it.

**Wilson, not the textbook formula.** The normal approximation reports zero
width at ten successes out of ten, which would let this system publish
"precision 1.00" from ten accounts with no visible hedge.

**PR curves, not ROC.** Under the class imbalance fraud always has, the false
positive rate has a vast denominator, so thousands of false positives barely
move it and a mediocre detector posts an impressive 0.95. Precision carries the
same false positives against the much smaller set of alerts, which is what
anyone staffing a review queue actually feels. Average precision is always
reported against chance level, because its floor is the base rate rather than
0.5.

---

## 4. The synthetic benchmark

**Circular by construction. These numbers measure the harness, not the
detector.** They are here because a harness that has never produced a number is
a harness nobody has checked.

Population: 50 accounts, 10 anomalous by construction (25 days of ordinary
history, then a burst of five payments ~1000× their own typical amount), 40
ordinary. Base rate 0.20 — absurdly high for fraud, and it makes the figures
*flattering* rather than conservative.

```
warnings:
  - 50 decisive labels is a small sample; read the confidence intervals
    rather than the point estimates
  - synthetic labels are included: these measure whether the detector finds
    anomalies this system generated, which is circular and is not validation

-- statistical composite, flagging at 0.50 --
  tp=1 fp=0 tn=40 fn=9  (50 accounts)
  precision=1.000 [0.21, 1.00]   recall=0.100 [0.02, 0.40]
  f1=0.182  base rate=0.200  lift=5.0
  average precision=0.280  (chance=0.200)
  best F1 at threshold 0.000: precision=0.200 recall=1.000

-- isolation score, flagging at 0.60 --
  tp=5 fp=0 tn=40 fn=5  (50 accounts)
  precision=1.000 [0.57, 1.00]   recall=0.500 [0.24, 0.76]
  f1=0.667  base rate=0.200  lift=5.0
  average precision=0.600  (chance=0.200)
  best F1 at threshold 0.649: precision=1.000 recall=0.500

-- by agreement state --
  BOTH_QUIET      n=45  anomalous share=0.11  [0.05, 0.23]
  BOTH_ELEVATED   n=1   anomalous share=1.00  [0.21, 1.00]
  ML_ONLY         n=4   anomalous share=1.00  [0.51, 1.00]
```

### Reading this honestly

> **Corrected in Phase 13.** This figure is an artefact of how this benchmark was
> built, not of the ceiling: nine of the ten anomalous accounts had no payments left
> inside the recent window when they were scored, so the amount and burst signals
> reported insufficient data. See
> [COMPOSITE_CEILING_FIX_REPORT.md](COMPOSITE_CEILING_FIX_REPORT.md) §5.

**The statistical layer missed nine of ten anomalies it was built to catch.**
Not because the signals failed — the amount signal fired at saturation on every
one of them — but because of the ceiling above. This is the finding, and the
benchmark's circularity does not weaken it, because the ceiling is arithmetic.

**Its best operating point is "flag everything."** At threshold 0.000, F1 is
0.333; at the configured 0.50, F1 is 0.182. A threshold at which flagging
nothing selectively beats flagging selectively is a threshold in the wrong
place.

**Average precision 0.280 against chance 0.200** is a weak but real signal: the
composite does rank anomalies above ordinary accounts, it just cuts in a place
that discards most of that ranking. This is precisely the distinction a PR curve
exists to make — a bad operating point is a config change, a bad ranking is a
model change, and this is the first.

**The isolation score did better on every measure**, and Phase 9's report
predicted the opposite posture ("the ML layer is not more authoritative than the
statistical one for being a model; if anything it is less"). On this benchmark
it is more sensitive, for a mundane reason: it has no weighted-mean ceiling.

### Phase 9's central claim, first evidence

Phase 9 kept the two scores apart on the argument that disagreement is the most
informative thing the pair produces, and called `ML_ONLY` "the row worth reading
first". Nothing had ever checked it.

Here, `ML_ONLY` accounts are anomalous 4 times out of 4, against 11% for
`BOTH_QUIET`. **That is directional support for the design decision.** It is
also n=4, on synthetic data, with an interval of [0.51, 1.00] — so it is the
weakest kind of support, and it is recorded as such. The number to watch when
real labels accumulate.

---

## 5. API surface

No `Idempotency-Key` anywhere: nothing here moves money, and the uniqueness that
matters comes from the data itself — one reviewer, one account, one instant — so
a retry returns the existing verdict.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/validation/labels` | Record a verdict. Replay-safe. |
| `GET` | `/validation/labels/{accountId}` | Every label, including superseded ones. |
| `GET` | `/validation/review/next` | Next account to judge. `?stratum=`, `?blind=` (default true). |
| `GET` | `/validation/review/census` | How many accounts sit in each pool. |
| `GET` | `/validation/report` | The evaluation. `?sources=` |
| `POST` | `/validation/labels/from-disputes` | Convert matured chargebacks. Explicit, never scheduled. |
| `POST` | `/admin/disputes` | Raise a chargeback, for demos. |
| `GET` | `/admin/disputes` | What the scheme believes. |

Two payload decisions worth stating. **Warnings come first** in the report, not
as a footer — a reader who skips them will quote precision from thirty accounts
as a property of the detector. And **`NaN` becomes `null`**, because Jackson
would otherwise emit the string `"NaN"`, which a consumer parses as a number,
coerces to zero, and charts as a precision of 0% — a detector that measured
nothing rendered as one that got everything wrong.

---

## 6. Findings during implementation

### FINDING-1 — the composite ceiling

Covered above. Found by measuring recall, sharpened by writing the test: the
first version asserted two signals suffice and failed, which is how the bar
turned out to be exactly three.

### FINDING-2 — what a positives-only feed measures

The dispute labeller's first documentation claimed it could estimate precision
and not recall. It is the other way round. Corrected in the same commit that
introduced the evaluator, and worth recording because the wrong version is the
intuitive one.

### FINDING-3 — a replay that returned a 500

`LabelService.record` was `@Transactional`, and the unique-index violation that
signals a replay aborts the transaction it occurs in — so the recovery query
that went looking for the existing label ran inside an aborted transaction and
failed with "current transaction is aborted". Idempotency that only works when
it is not exercised. Each repository call now gets its own transaction.

### FINDING-4 — a UUID compared against a VARCHAR

The dispute feed's external reference is free text, because that is what a
scheme sends; `payments.transaction_id` is a `uuid`. Postgres refuses the
comparison. Parsing in Java rather than casting in SQL also means a scheme
sending a reference that means nothing to us produces no label rather than an
error — the chargeback equivalent of Phase 5's
`UNEXPECTED_EXTERNAL_TRANSACTION`.

---

## 7. What this phase deliberately does not do

- **No tuning.** No weight fitted, no threshold moved, no model retrained. The
  ceiling finding is an argument for changing the weights and this phase does
  not act on it, because fitting against the label set you then evaluate on
  measures nothing.
- **No real-world validation.** There is still no human-labelled or
  dispute-labelled population large enough to support a figure. The machinery
  exists; the labels do not yet.
- **No change to detection.** The signals, the forest, the attribution, the
  agreement states and the narratives are exactly as Phases 8 through 11 left
  them.
- **No scheduled label generation.** Converting matured disputes is an explicit
  call, so a published figure can always be traced to a snapshot.

---

## 8. The standing caveat, finally narrowing

For four phases the caveat was flat: nothing is validated, because there is no
labelled data. It can now be stated more precisely, which is progress even
though the headline has not changed.

- **The detector is still unvalidated against reality.** Nothing here measured
  it against real fraud, because no real fraud has passed through this ledger.
- **What was measured is a structural property**, and structural properties do
  not need a representative sample: the composite ceiling holds for every
  account, and would hold if this system had a million real labels.
- **The synthetic figures measure the harness.** A detector that found anomalies
  designed around its own signals has demonstrated that the pipeline is
  connected, and nothing else.
- **Every real figure will arrive with an interval**, and the early ones will be
  wide enough to be embarrassing. That is the correct output for thirty labels,
  and the warnings exist so nobody quotes the midpoint without them.
- **Reviewer disagreement bounds all of it.** Until that is measured on real
  accounts, every precision figure has an unknown ceiling above it.

---

## Posture heading into Phase 13

The obvious next phase is the one this report argues for and refuses to do:
refit the weights and the threshold, with a held-out split, against a label set
large enough to support it. The ceiling finding gives that work a specific
target rather than a vague ambition — and the audit stratum, blind review and
leakage-safe joins mean the numbers it produces will mean something.

What is still missing is unglamorous and cannot be engineered: somebody has to
label accounts. The machinery now makes that labour count for as much as it
possibly can, which is the most this phase could honestly deliver.
