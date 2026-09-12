# Explanation Report — Phase 10

**Statistical explanation:** per-signal contributions that sum to the composite
**ML explanation:** isolation-bit attribution from the point's own tree paths
**Summaries:** template-generated · **Tests:** 99 · **Validation: still none**

Phases 8 and 9 produce two numbers per account and a four-way enum naming their
relationship. This phase turns those into something a reviewer can act on. It
adds no signal, changes no feature, and retrains nothing: every number explained
here was already being computed before this phase started.

The sentence that governs the whole document: **an explanation describes what
the models computed. It is not evidence about whether an account's behaviour is
actually anomalous, and it does not become so by being specific.**

---

## How to run it

From PowerShell, in the repository root:

```powershell
mvn test -D"test=StatisticalExplanationTest,ReconciliationTest,SummaryWriterTest,FeatureProvenanceTest,AttributionTest,PopulationProfileTest"
```

That is the 91 tests that need no Docker. The integration test needs a
container:

```powershell
mvn test -D"test=ExplanationFlowIntegrationTest"
```

> **PowerShell note:** quote the argument as `-D"test=..."`. Unquoted, PowerShell
> parses the `=` as an operator and expands any `*` before Maven sees it. In Git
> Bash use `-Dtest='...'`.

Against a running instance. A digest travels on the existing endpoints:

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>"
```

```powershell
curl.exe -s "http://localhost:8080/detection/anomalies?minScore=0"
```

The full explanation is its own endpoint:

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>/explanation"
```

> Use `curl.exe`, not `curl` — in PowerShell `curl` is an alias for
> `Invoke-WebRequest`, which takes entirely different arguments.

The ML half of every response is absent until a model exists:

```powershell
curl.exe -s -X POST "http://localhost:8080/detection/model/train"
```

---

## The statistical explanation

Phase 8 already made each signal explain itself, in a sentence written by the
code that did the arithmetic — `AmountOutlierSignal` knows the median, the scale
basis and the sample size, and nothing in this phase does or should. Recomputing
any of it here would create two places that can disagree about one account, and
the one a reviewer reads would be the one furthest from the calculation.

So the statistical explanation is an *arrangement* of Phase 8's output plus
exactly two new numbers per signal:

| Field | What it adds |
| --- | --- |
| `effectiveWeight` | the signal's fixed weight renormalised over the signals that could judge |
| `contribution` | `effectiveWeight × score` — the composite points this signal actually supplied |

> **Superseded in Phase 13.** `effectiveWeight` no longer exists: the composite
> does not renormalise, so there is nothing for it to be a fraction of.
> `contribution` is now this signal's **share** of the score, in `[0,1]`.

### The identity that makes it an explanation

> **Superseded in Phase 13.** The parts of Phase 13's power mean sum to the composite cubed, so contributions are now published as shares summing to 1.0. The identity is still exact and still pinned by a test; only its form changed. See [COMPOSITE_CEILING_FIX_REPORT.md](COMPOSITE_CEILING_FIX_REPORT.md).


```
sum(contribution) == composite
```

exactly, to floating-point accumulation, and a test pins it on hand-built scores
and again on real ledger data. A breakdown whose parts do not add up to the whole
is decoration.

The two new numbers matter because the same signal at the same score means
different things depending on what else could be measured. `amount_outlier` at
1.00 contributes 0.25 of a five-signal composite and 0.56 of a two-signal one.
Publishing only the raw weight would have left a reviewer to work out the
renormalisation themselves, and Phase 8's report is explicit that the
renormalisation is where that layer's sharpest trade-off lives.

Inapplicable signals stay in the list with a contribution of zero rather than
being dropped, for the same reason Phase 8 has three signal states rather than
two: a missing row reads as a signal that looked and found nothing, which is a
different and stronger claim than a signal that could not look.

---

## The ML explanation: choosing a method

### The problem

An Isolation Forest publishes `s(x, ψ) = 2^(−E(h(x))/c(ψ))` and nothing else.
There are no coefficients as in a linear model, no impurity gains as in a
decision tree classifier, no leaf values. The only artefacts are a mean path
length and 150 trees of random splits. **Every feature-level account of an
isolation score is therefore a reconstruction**, and the candidates differ in how
close the reconstruction sits to what the forest actually did.

### The three candidates

| Method | Cost | Faithfulness | Complexity |
| --- | --- | --- | --- |
| **A. Isolation-bit attribution** from the point's real paths | one extra traversal per tree, same order as scoring | high — reads the actual splits this point crossed | needs internal nodes to record subsample size |
| **B. Leave-one-feature-out re-scoring** | 11× scoring | medium — explains a point with a substituted value, not this one | needs a stored neutral value per feature |
| **C. Population-outlier heuristic** | trivial | low — can name a feature no tree ever split on for this point | almost none |

**B** reads well because its output is in score units ("0.78 → 0.55 without
this"), and that is its trap: the substituted point is off-manifold with respect
to trees that were built with the real value, so the difference measures a
counterfactual the model was never asked about. It costs an order of magnitude
more than A to be less faithful than A.

**C** is the cheap option, and it explains a *different model* — one that
considers each feature in isolation, which a forest emphatically does not. It
would produce confident-sounding sentences about features that had no part in
the score.

### Chosen: A, with C demoted to a labelled context column

Attribution answers *which* feature isolated the point. It structurally cannot
answer *which direction*, because a split is a threshold and a path is a
sequence of left-or-right turns: the same feature, the same bits, whichever side
the account is extreme on. "Isolated chiefly on `log10SecondsSinceLastPayment`"
is true and nearly useless.

So each attributed feature also carries its percentile against the training
population, and the two are separate fields with separate names on the wire.
The percentile is direction, nothing more. **It is not ranked on, it is not
mixed into the attribution, and the report and the caveat list both say it is
context rather than the model's reasoning.**

### The credit rule

At an internal node holding `n` rows, the point descends into a child holding
`m`. That split separated it from `n − m` rows, and is credited

```
log2(n / m)   bits of isolation
```

against the feature it split on: one bit for halving the sample, three for
cutting it to an eighth. Bits are accumulated per feature across all trees and
divided by the tree count.

Subtracting one bit per edge crossed — what an even split would have yielded —
gives **excess bits**, which is the number the output ranks on:

```
excessBits = isolationBits − splitsPerTree
```

Positive means the feature isolated this point faster than a coin flip would.
Negative means its splits repeatedly put the point on the crowded side, which is
positive evidence of ordinariness on that axis and is reported as such rather
than being clipped to zero.

**Bits rather than edges, and the difference decides whether the output is an
explanation at all.** Crediting one unit per edge would attribute *depth*, and
depth is what normal points accumulate; the top of that ranking would be
whichever feature the random attribute selection happened to draw most often.
Bits measure separation achieved, which is what the score is made of.

Both partitions at an internal node are non-empty by construction — a split that
emptied one side becomes an external node — so `m < n` always and every credit
is strictly positive.

### Faithfulness, stated honestly

This is the section the phase brief asks for, and the honest answer has four
parts:

1. **It is faithful about paths, not about the score.** The credits do not sum to
   the score and are not Shapley values. They describe how the point got
   separated; they do not decompose `2^(−E(h)/c(ψ))` into additive parts. Nothing
   in the output claims otherwise, and `share` is documented as a share of
   isolation rather than of score.
2. **Correlated features steal credit from each other.** `amountModifiedZ`,
   `log10LargestRecentAmount` and `historicalAmountPercentile` are three framings
   of one behaviour. Whichever a tree drew first did the separating, and the
   others were never asked. In the worked `BOTH_ELEVATED` example below the three
   split 70/24/6 — the ordering is real, but the exact split is partly an
   artefact of draw order.
3. **It is a Monte-Carlo estimate.** Splits are random, so attribution has
   variance across trees. It is damped by averaging over 150 of them and it is
   fully deterministic for a given seed, but a differently seeded forest on the
   same data would rank near-tied features differently.
4. **A feature that was never selected earns nothing**, even if it would have
   isolated the point. This is not silent: `splitsPerTree` is published, and
   zero splits is visibly different from many splits that achieved nothing.

Leave-one-feature-out remains available as future work if explanations in score
units are ever wanted alongside these; it would answer a different question, not
a better version of this one.

### What it cost the model: nothing

Internal nodes now record their subsample size, which the scoring path never
reads — `pathLength` only needs the size of the external node it lands on. No
split, no path and no score changes. A forest trained by this code scores
identically to one trained before the explanation layer existed, and the
determinism tests carried from Phase 9 still pass unchanged.

The explained score is produced by `score()` rather than recomputed, and
`E(h(x))` is inverted back out of it by Equation 2 rather than accumulated a
second time during the walk. A second accumulation would be a second definition
of one quantity, and the two would eventually disagree by a rounding step in a
way that made the explanation look wrong about the score it was explaining.

---

## Reconciling the two layers

### The thing this must not do

The easy output is one smooth narrative per account, written so that two layers
disagreeing reads like two layers agreeing. That would undo Phase 9's central
decision at the presentation layer: the scores were kept apart precisely because
disagreement is the most informative thing the pair produces, and averaging the
*prose* loses exactly what refusing to average the *numbers* preserved.

### Three kinds of visibility, not two

Saying "the statistical layer does not see this" requires knowing what it sees.
A binary split would have been wrong in both directions, so `FeatureProvenance`
carries three:

| Visibility | Features | Meaning |
| --- | --- | --- |
| `SIGNAL_STATISTIC` | `amountModifiedZ`, `velocitySurprisal`, `burstSurprisal`, `mismatchSurprisal`, `returnSurprisal` | literally a signal's own raw statistic |
| `SAME_AXIS` | `recentPaymentCount`, `historicalAmountPercentile`, `returnedPaymentFraction`, `dataCompleteness` | a different measurement of a question some signal also asks |
| `OUTSIDE` | `log10LargestRecentAmount`, `log10SecondsSinceLastPayment` | nothing in the statistical layer asks this |

`recentPaymentCount` is the case that forces the middle row. The velocity signal
counts the same payments, so calling the feature invisible would manufacture a
disagreement; but velocity asks whether the count is high *for this account* and
the raw count is comparable *across accounts*, so calling it visible would
suppress one. Only `OUTSIDE` licenses the claim that no signal looks at
something — and the two features there are exactly the two Phase 9 added because
Phase 8 structurally cannot express them.

### Corroboration is not two elevated scores

`corroborated` is true when a signal that **fired** shares an axis with a feature
that **isolated**. It is deliberately not the same thing as `BOTH_ELEVATED`, and
the combination worth reading carefully is `BOTH_ELEVATED` with
`corroborated: false`: two elevated numbers resting on unrelated evidence, which
looks like confirmation in a ranking and is not. That case gets its own sentence
saying so.

### The four narratives

| State | What the narrative says |
| --- | --- |
| `BOTH_QUIET` | neither elevated, and quiet in both layers is the weakest claim either can make rather than a clean bill of health |
| `BOTH_ELEVATED` | corroborating or not; when corroborating, also which part of the model's reasoning the shared axis does **not** cover |
| `STATISTICAL_ONLY` | unusual for this account, ordinary for the population — and the competing reading, that the model's features miss what the signals caught |
| `ML_ONLY` | what it isolated on, whether any signal can see that at all, and whether a matching signal fired but was diluted below the threshold |

A test asserts that the four produce four different strings. A narrative that
read the same whichever way the layers fell would undo the design without
failing anything else.

---

## Summary generation

### Templates, and why not an LLM

The brief allows an LLM call if it can be justified. It cannot, on three counts:

1. **Determinism.** Phases 7, 8 and 9 all hold the line that the same ledger
   gives the same output, and the detection tests depend on it. A sampled model
   breaks that, and the honest fix — asserting on a paraphrase — is precisely the
   flaky test this phase is told not to write.
2. **The hedging is the hard requirement.** These sentences must never claim an
   account is doing something wrong. A template cannot drift into "clearly
   fraudulent" on a Tuesday; a generative model can, and the safeguard would be a
   test asserting the absence of vocabulary the model was free to invent.
3. **There is nothing to generate.** The content is a closed set of already
   computed facts. An LLM would rephrase them, and would add a network dependency
   to a read endpoint to do it.

Where a model would genuinely help is the thing this system does not have:
turning counterparties, payment descriptions and timing-against-business-hours
into context no feature encodes. That is a different capability, not a better
renderer for this one, and it is noted here as future work rather than smuggled
in as a formatting choice.

### The vocabulary rule

Every sentence describes a **departure from a named reference** — this account's
own history, or the population the model trained on. A test asserts that no
generated summary contains any of fifteen words that assert wrongdoing or
certainty (`fraud`, `suspicious`, `confirmed`, `proves`, `definitely`, …),
whatever the account was isolated on. The templates exist partly so that sentence
is *unreachable* rather than merely unlikely.

Caveats are a separate list rather than a closing paragraph, because a
limitation folded into prose is a limitation nobody reads. Two are standing; the
rest are earned by the specific account — thin evidence, signals that could not
judge, the attribution credit rule, the percentile context.

---

## API surface

No writes. Everything below is a GET.

| Endpoint | Change |
| --- | --- |
| `GET /detection/accounts/{id}` | `signals[]` now carries `effectiveWeight` and `contribution` and is ordered by contribution; new `explanation` digest |
| `GET /detection/anomalies` | same digest on every row; ranking unchanged and still not by a blended score |
| `GET /detection/accounts/{id}/explanation` | **new** — full breakdown, all 11 attributions, reconciliation, summary, caveats |

Five signal contributions and eleven feature attributions with prose apiece is
roughly four kilobytes of JSON per account. On a fifty-row ranking that drowns
the thing the caller asked for, which was *which accounts to open*. So the list
carries a digest and the detail lives at its own endpoint — the same split the
Phase 5 endpoints draw between a reconciliation run and its incidents.

What the digest does **not** defer is the qualification. The generated summary
travels in full, closing sentence included, so a caller that never follows the
link still cannot read an unqualified finding. Only the per-account caveat list
waits at the detail endpoint.

The change to `signals[]` is additive on the wire: same field names as Phase 9's
`SignalDetail`, plus the two new numbers, in a more useful order.

---

## Worked examples

### How these were produced

A population of 230 accounts assembled from the Phase 8 test fixtures: 200
ordinary ones with varied unremarkable histories, a cluster of 25 that all do the
same moderately unusual thing, and five deliberately unusual ones. Scored by
`AnomalyScorer`, featurised by `FeatureExtractor`, trained and attributed by the
same `IsolationForest` the endpoints use — the whole pipeline except the Postgres
ledger, which is exercised instead by `ExplanationFlowIntegrationTest`.

The distribution that fell out, which is itself worth reading:

| State | Accounts |
| --- | --- |
| `BOTH_QUIET` | 199 |
| `BOTH_ELEVATED` | 1 |
| `STATISTICAL_ONLY` | 25 |
| `ML_ONLY` | 5 |

The 25 `STATISTICAL_ONLY` rows are the cluster, and they are the clearest
demonstration in this document of what the two layers are for: each of those
accounts is extraordinary against its own history and utterly ordinary among its
peers, and only one of the two layers can tell you that.

### BOTH_ELEVATED — and corroboration that covers only part of the reasoning

An account with thirty payments of about 30.00 USD that suddenly sends 90,000.

```
composite 0.556 over 2 applicable signals; isolation 0.675

amount_outlier   applicable=true  score=1.000 weight=0.25 effective=0.556 contribution=0.556
    90000.00 USD is 12642.5 robust deviations above this account's usual 30.00 USD
    (30 prior payments, scale from median absolute deviation)
velocity         applicable=true  score=0.000 weight=0.20 effective=0.444 contribution=0.000
burst, reconciliation_mismatch_rate, refund_reversal_rate: insufficient data

amountModifiedZ             value=12642.486  excessBits=+2.882  share=0.70  pct=1.00
log10LargestRecentAmount    value=    6.954  excessBits=+1.011  share=0.24  pct=1.00
historicalAmountPercentile  value=    1.000  excessBits=+0.229  share=0.06  pct=1.00
log10SecondsSinceLastPayment value=   2.258  excessBits=+0.013  share=0.00  pct=0.03
dataCompleteness            value=    0.400  excessBits=-0.009  share=0.00  pct=0.21
```

> Both layers are elevated and they point at the same behaviour: amount_outlier
> fired statistically, and the model isolated this account chiefly on
> amountModifiedZ (70% of the isolation, above the population median at the 100th
> percentile), log10LargestRecentAmount (24% …) and historicalAmountPercentile
> (6% …). The shared axis is amount_outlier. **The model also isolated on
> log10LargestRecentAmount, which no statistical signal sees, so the
> corroboration covers part of its reasoning rather than all of it.**

Note the three amount framings splitting 70/24/6 — the correlated-features
caveat, visible in a real output.

### STATISTICAL_ONLY — unusual for itself, ordinary for its peers

One member of the cluster: thirty payments of about 30.00 USD, then 2,400.

```
composite 0.556 over 2 applicable signals; isolation 0.458

amount_outlier   applicable=true  score=1.000 effective=0.556 contribution=0.556
    2400.00 USD is 333.0 robust deviations above this account's usual 30.00 USD

historicalAmountPercentile    value=1.000  excessBits=+0.220  share=0.65  pct=1.00
log10SecondsSinceLastPayment  value=2.258  excessBits=+0.117  share=0.35  pct=0.03
dataCompleteness              value=0.400  excessBits=-0.026  share=0.00  pct=0.21
```

> The statistical layer is elevated and the model is not. Amount_outlier fired
> against this account's own history, but at 0.46 the isolation score is below the
> 0.60 convention: across the 230 accounts the model was trained on, behaviour
> like this is not rare enough to isolate. Unusual for this account and ordinary
> for the population is the ordinary reading; the other reading is that the
> model's features do not capture what the signals caught.

The same behaviour that scored 0.675 above scores 0.458 here, and the only
difference is that twenty-four other accounts did the same thing. That is the
model working as designed, and it is also exactly why the statistical layer was
not replaced by it.

### ML_ONLY — the row the parallel design exists to surface

An account with a fifth of its payments returned and one reconciliation
mismatch, none of it enough for any signal's minimum sample size.

```
composite 0.000 over 2 applicable signals; isolation 0.663

amount_outlier:        insufficient data: no payments in the recent window to judge
refund_reversal_rate:  insufficient data: 5 payments were refunded or reversed in the
                       window, need 10 before a proportion means anything
reconciliation_mismatch_rate applicable=true score=0.000 contribution=0.000
    1 of 11 transactions raised a reconciliation incident, a rate of 9.1 against the
    ledger-wide 2.0 percent; p = 0.200

returnedPaymentFraction      value=0.200  excessBits=+1.551  share=0.44  pct=0.99
mismatchSurprisal            value=0.700  excessBits=+1.547  share=0.43  pct=0.99
log10SecondsSinceLastPayment value=4.937  excessBits=+0.184  share=0.05  pct=1.00
```

> The model is elevated and the statistical layer is not. It isolated this account
> on returnedPaymentFraction (44% …), mismatchSurprisal (43% …) and
> log10SecondsSinceLastPayment (5% …), while the composite of 0.00 rests on no
> signal at all. The statistical layer does not see log10SecondsSinceLastPayment
> at all, so there is nothing in the composite to corroborate or contradict this.

The statistical layer is not wrong here — its minimums exist for good reasons and
five returned payments genuinely is too few to call a rate. The model has no such
scruple, and whether that makes it right or merely louder is unknown, which is
the whole reason both numbers are published.

### BOTH_QUIET — including what "quiet" is not

```
composite 0.000 over 4 applicable signals; isolation 0.360
every attributed feature: excessBits negative
```

> Neither layer is elevated: a composite of 0.00 over 4 applicable signal(s) and
> an isolation score of 0.36, where roughly 0.5 is the middle of the distribution
> rather than a threshold. Quiet in both layers is the weakest claim either can
> make, not a clean bill of health.

All eleven features negative on excess bits is the correct output for an ordinary
account, and it is worth having rather than an empty list: it says the forest
looked and kept putting this account on the crowded side.

---

## Findings during implementation

### FINDING-1 — an `ML_ONLY` row where the statistical layer had seen it perfectly well

An account with fourteen reconciliation mismatches in forty transactions came out
`ML_ONLY`, and the narrative reported that the statistical layer was not
elevated. True, and misleading: `reconciliation_mismatch_rate` had fired at 1.00
of its own scale with `p = 2.41e-14`. The composite is a weighted mean over the
applicable signals, so one signal at full strength among four cannot exceed its
own effective weight of 0.25 — and it stayed below the 0.50 convention.

(That account sits in an earlier, smaller version of the example population
above; with the cluster added it is no longer isolated at all. The case is
pinned by a unit test rather than by a population that can shift under it.)

Calling that a disagreement is wrong about a signal that saw the behaviour and
was averaged down. `ML_ONLY` now distinguishes the two: when the isolating axis
belongs to a signal that fired, the narrative says the layers are not
disagreeing, the statistical score is diluted. This is Phase 8's renormalisation
trade surfacing in the explanation layer exactly where that phase's report
predicted the cost would land.

### FINDING-2 — corroboration standing in for the whole explanation

The `BOTH_ELEVATED` example above corroborated on `amount_outlier`, which was
true, while the model had also isolated 24% of its bits on
`log10LargestRecentAmount`, which no signal sees. The narrative named the shared
axis and stopped, letting partial corroboration read as complete. It now says
which part is covered.

Both findings came from writing the worked examples rather than from writing the
tests, which is the second phase running where generating the report found
something the implementation had missed.

---

## What this phase deliberately does not do

- **No new signals, no feature changes, no retraining.** Explanation only.
- **No blended score, still.** Nothing here produces a single number, and the
  ranking is unchanged.
- **No calibration of the elevation thresholds.** 0.50 and 0.60 remain
  conventions; this phase makes their consequences more visible without making
  them more principled.
- **No LLM anywhere in the read path.**
- **No persistence of explanations.** They are recomputed per request from the
  ledger and the in-memory model, for the same reason Phase 8 stores no derived
  state: a stored explanation can disagree with the rows it describes.

---

## The standing caveat, restated

Everything in this document is a description of computation.

- Neither score is validated. There is no labelled data in this system, so
  precision and recall are **unmeasured rather than approximately known**.
- The statistical weights are unfitted judgement. The elevation thresholds are
  conventions.
- The feature attribution is one credit rule's reconstruction of what a forest
  of random trees did. It is faithful to the paths and it is not a unique
  decomposition.
- The population percentiles describe a training snapshot, which is itself
  assumed — and cannot be checked — to be mostly normal.
- A confident-sounding explanation of an unvalidated score is still an
  unvalidated score. **The explanations in this phase make the two models
  legible; they do not make them right.**

---

## Posture heading into Phase 11

The system can now say why it says what it says, in two layers that are not
allowed to average themselves together, with the disagreements between them made
harder to overlook than the agreements. What it still cannot say is whether any
of it corresponds to anything.

Every road out of that leads through labels: a reviewer marking outcomes, a
chargeback feed, anything that turns "this account deviates from X in ways Y"
into "and here is what happened next". Until then the most useful thing this
layer does is make its own uncertainty inspectable, which is not the same as
reducing it.
