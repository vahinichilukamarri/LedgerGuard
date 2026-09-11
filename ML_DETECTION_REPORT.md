# ML Detection Report — Phase 9 (Isolation Forest)

**Model:** Isolation Forest, hand-rolled · **Features:** 11 · **Tests:** 47
**Integration:** parallel score, never blended · **Validation: none possible yet**

Phase 8 built five statistical signals with thresholds taken from the
literature. This phase adds a learned model beside them. It does not replace
them, it is not combined with them, and — stated at the top because it is the
most important sentence in this document — **it is not more trustworthy than they
are just because it is a model.**

---

## How to run it

From PowerShell, in the repository root:

```powershell
mvn test -D"test=IsolationForestTest,FeatureExtractorTest,AgreementTest"
```

That is the 39 tests that need no Docker. The integration test needs a
container:

```powershell
mvn test -D"test=MlDetectionFlowIntegrationTest"
```

> **PowerShell note:** quote the argument as `-D"test=..."`. Unquoted, PowerShell
> parses the `=` as an operator and expands any `*` before Maven sees it. In Git
> Bash use `-Dtest='...'`.

Against a running instance — a model must be trained before it will score
anything:

```powershell
curl.exe -s -X POST "http://localhost:8080/detection/model/train"
```

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>"
```

```powershell
curl.exe -s "http://localhost:8080/detection/anomalies?minScore=0"
```

> Use `curl.exe`, not `curl` — in PowerShell `curl` is an alias for
> `Invoke-WebRequest`, which takes entirely different arguments.

---

## Model choice: hand-rolled, in process

| Option | Decision |
|---|---|
| **Hand-rolled Java** | **Chosen** |
| Smile / Tribuo / Weka | Rejected: tens of megabytes of transitive dependencies for one algorithm |
| Python sidecar (scikit-learn) | Rejected: a deployment unit, a network hop, a second runtime, cross-language RNG reproducibility risk |

Writing a machine learning algorithm from scratch is usually a poor trade.
Three things make it the right one here, and none of them is "it was fun":

1. **The algorithm is small and completely specified.** Every part of the
   implementation corresponds to a numbered algorithm or equation in a nine-page
   paper — Liu, Ting and Zhou, *Isolation Forest*, ICDM 2008 — and those
   citations are **inline in the code**, not only in this report, so the
   isolation-score implementation can be checked against the source line by
   line.
2. **The required tests would otherwise be impossible.** This phase must unit
   test tree construction, path-length scoring and the isolation score formula.
   You cannot write those against a library's internals, and you certainly
   cannot write them across a sidecar's process boundary. A dependency would not
   have made the tests inconvenient; it would have made them unwritable.
3. **Determinism needs local control of the RNG.** Phases 7 and 8 established
   that nothing in this system varies silently between runs. A library's
   internal use of its own generator is not part of its published contract and
   can change between versions.

**What is given up:** none of scikit-learn's battle-testing, and any later want
of Extended Isolation Forest or SCiForest means writing those too.

### How the algorithm works, in one paragraph

Split the data on a random feature at a random value, repeatedly. A point in a
dense region needs many splits before it is alone; a point far from everything
else is separated almost immediately. The **depth at which a point becomes
isolated** is therefore itself an anomaly score, requiring no distance metric, no
density estimate and no distributional assumption — which suits a feature set
whose members are on wildly different scales and none of which is normally
distributed.

### Two cases the paper leaves implicit

Both are real, both are handled explicitly, and both have tests:

- **A constant attribute** has no split point between its minimum and maximum.
  Selecting attributes uniformly and retrying would loop forever when every
  attribute is constant, so selection draws only from attributes that vary in
  the subsample, and a node where none do becomes a leaf.
- **An empty partition.** The split point is drawn from `[min, max)`, so it can
  land exactly on the minimum and leave one side empty. Such a node has isolated
  nothing, so it becomes a leaf rather than consuming a level of depth.

### Reading the score

`s(x, ψ) = 2^(−E(h(x))/c(ψ))` is already in `[0,1]`, which is why no extra
normalisation layer sits between the model and the rest of the system — a layer
like that is somewhere for a bug to hide, and the algorithm makes it
unnecessary.

**Scores concentrate near 0.5 by construction.** A point of average depth scores
exactly one half, so **0.5 is the middle of the distribution, not a threshold**.
Values approaching 1 mean isolated far sooner than average; in practice anything
above about 0.6 is notable and nothing in real data approaches 1.0. Anyone
reading 0.5 as "half anomalous" will misread every score this produces.

---

## The feature set

Eleven features. The first five are Phase 8's **raw statistics**, deliberately
not the `[0,1]` scores it publishes.

| # | Feature | Why it is here |
|---|---|---|
| 1 | `amountModifiedZ` | Phase 8's MAD-based z-score, **kept signed**. Direction is information: a payment far below an account's usual is card testing, far above is something else, and the model can learn them separately. Phase 8 takes the magnitude because it needs one number for a human |
| 2 | `velocitySurprisal` | rate departure, Poisson tail |
| 3 | `burstSurprisal` | clustering, Bonferroni-corrected scan |
| 4 | `mismatchSurprisal` | reconciliation failure rate vs. population |
| 5 | `returnSurprisal` | refund/reversal rate vs. population |
| 6 | `log10LargestRecentAmount` | **The one thing Phase 8 structurally cannot see.** Every statistical signal is relative to the account's own history — there is deliberately no absolute threshold anywhere in that layer. A forest trains across the population, so it can learn that some magnitudes are rare *everywhere*, which is a different question from whether they are rare for this account |
| 7 | `recentPaymentCount` | cross-account comparable count, where velocity surprisal is relative to the account's own rate |
| 8 | `log10SecondsSinceLastPayment` | dormancy followed by activity. No Phase 8 signal covers it: velocity sees rate, burst sees clustering, neither notices an account waking up after six months |
| 9 | `historicalAmountPercentile` | rank of the largest recent amount within the account's own history. Bounded, assumes no distribution shape, and still separates "largest ever" from "merely large" when the z-score has saturated or its scale estimate collapsed |
| 10 | `returnedPaymentFraction` | the account's own return rate, where feature 5 is against the population's |
| 11 | `dataCompleteness` | the fraction of signals that had enough data to judge. See the imputation section below |

### Why raw statistics, not the published scores

`Signal.normalise()` clamps everything below its threshold to zero and everything
above saturation to one. That is exactly right for a human-readable composite and
exactly wrong as model input: it **destroys the ordering at both ends**, which is
most of the information. An account at surprisal 4 and one at surprisal 40 are
the same number after normalisation and very different rows in a forest.

This is the same principle as Phase 8's decision to compute at query time rather
than from a stored aggregate: do not hand the next layer a version of the data
that has already had its information removed.

### Scaling: none, and none needed

A forest splits uniformly between the minimum and maximum of a feature *within
the subsample*, so it is **scale-invariant by construction** — no
standardisation, no unit variance, and none of the bugs those bring.

It is **not shape-invariant**, which is why features 6 and 8 are log-transformed
and nothing else is. A feature spanning many orders of magnitude puts nearly
every uniform split in its sparse tail, wasting splits and leaving the forest
blind to structure at the dense end.

### Correlated features, acknowledged

(2, 7) and (5, 10) measure related things. A forest tolerates correlation; the
effect is that random feature selection collectively picks that aspect slightly
more often, mildly over-weighting it. Stated rather than pretended away.

---

## The imputation problem

**This section deserves the same weight as Phase 8's unfitted-weights caveat, and
is written to be read that way.**

A Phase 8 statistic is `NaN` when its signal had too little data to judge, and a
forest needs a number. Those are **imputed as zero** — the value a perfectly
unremarkable account produces — so the imputation reads as "nothing to see"
rather than as a distinct sentinel the forest would isolate on.

That deliberately conflates two different things: *measured, and unremarkable*
with *not measurable at all*. `dataCompleteness` exists to make the conflation
visible to the model rather than silent, so the forest can learn that thin-history
accounts are their own population.

> **This exposes the problem; it does not solve it.** If most of the training
> population has thin data, the imputed zeros dominate and **the model learns the
> imputation rather than the behaviour** — it will separate accounts by how much
> history they have, not by how they behave, and the resulting scores will look
> entirely reasonable while measuring the wrong thing. On the small ledgers this
> phase can currently be demonstrated against, that is the *likely* case, not a
> remote one.

There is no fix available in this phase, because the fix is more data. What is
available is saying so.

---

## Training data and the bootstrap problem

**Source:** every account's activity vector as of a chosen instant, taken from
the ledger and reconciliation tables Phase 8 already reads. Nothing is
synthesised and nothing is labelled.

Isolation Forest is **unsupervised by design**. Training on unlabelled historical
activity is native to the algorithm, not a workaround for missing labels — the
algorithm never sees labels even when they exist.

### What that does not excuse

The algorithm still assumes anomalies are **few and different**. If the ledger
contains many anomalous accounts, they become part of what the model considers
ordinary. **Nothing here can check that assumption**, because checking it would
require knowing which accounts are anomalous, which is the thing being sought.
The contamination rate is unknown and unknowable in this phase.

### The population gate

Below **32 accounts**, training is refused rather than performed. A forest
isolates points that are few and different; on a handful of accounts almost every
point is few and different, and the model becomes an elaborate way of reporting
that the dataset is small. Serving that quietly would be worse than serving
nothing, because **a number carries an authority that an absence does not**.

Same instinct as Phase 8's three signal states: an absence of evidence must not
be able to present itself as evidence of absence.

---

## Validation: none, and what that means precisely

**There is no labelled data, so precision and recall are unmeasured and
unmeasurable in this phase.** Not "approximately known", not "expected to be
good" — unmeasured.

What the 47 tests do establish:

- The forest implements the paper. `c(10) = 3.7488806` matches the hand
  calculation from Equation 1; a point of average depth scores exactly one half,
  which is the identity Equation 2 rests on.
- The feature pipeline computes what it claims to, checked against arithmetic
  done by hand.
- The same seed and the same ledger reproduce every score exactly, through a full
  retraining.
- An account constructed to be unlike the population scores above the median.

That last one is the one most easily over-read, so, in the same spirit as Phase
8 saying its weights are *unfitted, not learned*:

> **Sanity-checking synthetic anomalies-by-construction tells you the model isn't
> broken, not that it's useful on real data.** A forest that separates a point at
> (12, −11) from a Gaussian cloud has demonstrated that its arithmetic works. It
> has demonstrated nothing whatsoever about whether real fraud looks like an
> outlier in *this* feature space, on *this* ledger. Those are different claims,
> and only the first one has been made.

Measuring the second requires labelled outcomes — confirmed fraud, confirmed
false positives — which this system has never had and cannot manufacture. Until
then the model is a hypothesis with a test suite, not a validated detector.

---

## Integration: a parallel score, never blended

The model is **not** a sixth Phase 8 signal. Both scores travel side by side with
distinct names, and an `agreement` field names their relationship rather than
resolving it.

```
statisticalScore : 0.62      <- Phase 8 composite, explainable signal by signal
mlScore          : 0.71      <- isolation score, not explainable
agreement        : BOTH_ELEVATED
```

### Why not folded in

Four reasons, all pointing the same way:

1. **It would need a weight** — a sixth unfitted judgement stacked on the five
   Phase 8 already carries.
2. **It would make the composite partly unexplainable.** Every statistical signal
   states its reasoning in a sentence. A forest score cannot. Blending them
   produces a number whose provenance simply stops part way through — and Phase
   10 is an explanation layer, which would inherit that hole.
3. **Disagreement is the most useful thing here.** An account the statistics call
   quiet and the model calls extreme is the single most interesting row in the
   system: either the model found structure the signals cannot express, or the
   model is wrong. Both are worth knowing, and averaging destroys exactly that.
4. **Neither score is validated.** Combining two unvalidated numbers produces one
   unvalidated number that *looks more authoritative for being single*, which is
   the specific failure this phase exists to avoid.

### The four states

| Agreement | Meaning |
|---|---|
| `BOTH_QUIET` | neither elevated |
| `BOTH_ELEVATED` | both — the only case where the two layers corroborate |
| `STATISTICAL_ONLY` | unusual for this account, ordinary across the population |
| `ML_ONLY` | **the row worth reading first** — a feature combination no single signal is shaped to catch, or the model reacting to something that does not matter |

### The elevation thresholds

`statisticalScore ≥ 0.5`, `mlScore ≥ 0.6`. **Both are conventions, not fitted
values** — the same caveat as Phase 8's weights.

They are deliberately *different numbers*. The statistical composite is a
weighted mean of scores that sit at zero until their signal fires, so 0.5 there
means a substantial share fired. An isolation score concentrates around 0.5 by
construction, so the same cut-off would call roughly half the population
elevated. One shared constant would be tidier and wrong; a test pins them apart
so a later "simplification" cannot quietly merge them.

### The ranked endpoint is not ranked by a blend

An account appears in `/detection/anomalies` if **either** layer flags it, ordered
by the statistical composite with the isolation score as tie-break, each row
carrying its `agreement`. Ranking by a combined number would have reintroduced
the conflation the whole design avoids, and would have buried every `ML_ONLY` row
beneath accounts the statistics merely mildly disliked.

---

## Retraining and staleness

### What Phase 9 does

- **Explicit training only**, via `POST /detection/model/train`. No lazy training
  on first request: a model appearing as a side effect of the first read would be
  trained on whatever the ledger held at that moment, by whoever called first,
  and nobody would know which snapshot they got.
- **Full provenance on every score** — seed, tree count, subsample size, training
  population, `trainedAt` and `trainedAsOf`. A statistical signal can be
  recomputed by anyone who reads its formula; a forest score cannot, so the seed
  and the training snapshot are the only things that keep it reproducible rather
  than merely believed.
- **"No model" is a distinct answer** from a score of zero, and is reported as
  such.

### What Phase 9 defers, and why

- **Scheduled retraining.** Straightforward to add; deliberately not added while
  there is no way to tell whether a retrain improved anything.
- **Persistence across restart.** The model lives in memory and is lost on
  restart. Acceptable for a demonstrable phase, and stated rather than hidden.
- **Drift detection.** This is the interesting deferral. Without labels you can
  only detect drift in the *input* distribution, never in *accuracy*. Acting on
  input drift alone is guesswork: a population whose behaviour genuinely changed
  and a population being attacked look identical from the input side.
- **Champion/challenger.** Comparing two models requires a metric to compare them
  on. There isn't one yet.

### The staleness problem, stated plainly

The model encodes a snapshot of what "normal" looked like at `trainedAsOf`. As
the ledger grows, that notion goes out of date — and specifically, **behaviour
that was anomalous at training time becomes normal if it later becomes common**,
with nothing to announce that it has happened. `trainedAsOf` travelling on every
response is what makes a stale model visibly stale rather than quietly current.

---

## Findings during implementation

### FINDING-1 — every score was NaN for a single-row subsample

Found by `IsolationForestTest.singleRowIsRefused`, not by reasoning about the
code.

With a subsample of one point, `c(ψ) = 0` by Equation 1, and Equation 2 then
divides by it. Every score came back `NaN` — silently, for every input, with
nothing in the arithmetic to announce it, and `NaN` propagates through everything
downstream that touches it.

**Fix:** a forest over one observation cannot isolate anything from anything, so
it is refused at construction. The test pins both sides: one row refused, two
rows scoring finite. Note the service-level population gate (32) would have
prevented this in practice — but a library class that returns `NaN` when misused
is a trap for the next caller, and the next caller might not have a gate.

### FINDING-2 — two test expectations of mine were wrong, not the code

Recorded because the corrections were to the tests:

- `dataCompleteness` returned 0.8 where the test expected 1.0. Correct: burst
  needs three payments in the window and the fixture had one. The test now pins
  both fractions and explains why they differ.
- The training population was one larger than the payer count, because the
  shared payee is an account too. Now asserted against the accounts table rather
  than against the test's own arithmetic — which is what got it wrong.

---

## Posture heading into Phase 10

What this phase hands over:

- A working Isolation Forest whose implementation can be checked against a
  published paper line by line.
- Two scores that are **deliberately not reconciled**, so Phase 10's explanation
  layer inherits a fully explainable statistical composite and a separately
  labelled model score, rather than one number it can only partly explain.
- An `agreement` field that is itself an explanation hook: `ML_ONLY` is a
  question worth putting in front of a human.
- Full model provenance on every response.

What it does not hand over is any claim to accuracy. Phase 8 ended with unfitted
weights; Phase 9 ends with an unvalidated model. Neither is dressed up as more
than it is, and **the ML layer is not more authoritative than the statistical one
for being a model** — if anything it is less, because the statistical layer can at
least explain itself.
