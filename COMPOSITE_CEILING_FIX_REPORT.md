# Composite Ceiling Fix — Phase 13

**Change:** the aggregation function only · **New constants:** none
**Guarantee:** any one saturated signal elevates any account · **Tests:** 536
**Labels used to fit anything:** none, deliberately

Phase 12 found that Phase 8's composite could not flag an account on one signal
however extreme that signal was. This phase characterises the defect exactly,
replaces the aggregation, and reports what that did and did not change.

The headline is not the fix. It is that **re-running Phase 12's benchmark turned
up two construction defects in the benchmark itself**, and that the recall
number Phase 12 published was measuring those rather than the ceiling. Both
things are true at once: the ceiling was real, and the evidence offered for it
was not.

---

## 1. The ceiling, characterised

Phase 12 reported two endpoints — five applicable signals need three saturated,
thin-history accounts need fewer — and inferred a ramp. Enumerating all 31
applicable sets against every saturating subset shows a cliff.

| Applicable | Min saturated to flag | Can one signal ever flag? |
|---|---|---|
| 1 | 1 of 1 (composite reads **1.00**) | yes, all 5 sets |
| 2 | 1 of 2 | yes, all 10 sets |
| 3 | 2 of 3 | **no, 0 of 10 sets** |
| 4 | 2 of 4 | **no, 0 of 5 sets** |
| 5 | 3 of 5 | **no** |

Two things the endpoints hid:

- **Four applicable needs two, not three.** The count does not rise
  monotonically with measurability.
- **The cliff is at three.** The operationally important property is not that a
  fully-measured account is hard to flag; it is that a single signal's ability
  to flag an account *vanishes the moment a third signal becomes measurable*,
  and no extremity recovers it.

Stated as a mechanism: under a renormalised mean, **accruing history can only
lower an account's composite**, holding behaviour fixed. A signal that gains
enough data to say "nothing unusual here" enlarges the denominator and dilutes
the ones that are shouting.

---

## 2. Candidates

Minimum single-signal score needed to flag (range = easiest to hardest signal):

| Applicable | Legacy (p=1) | Renormalised p=3 | **Plain p=3** | max(mean, top×0.6) |
|---|---|---|---|---|
| 1 | 0.500 | 0.500 | 0.794–0.941 | 0.500 |
| 2 | 0.800–0.900 | 0.585–0.693 | 0.794–0.941 | 0.600 |
| 3 | impossible | 0.669–0.794 | 0.794–0.941 | 0.600 |
| 4 | impossible | 0.737–0.874 | 0.794–0.941 | 0.600 |
| 5 | impossible | 0.794–0.941 | 0.794–0.941 | 0.600 |

Every candidate closes the ceiling. Only one has a **flat** column, and that is
what decided it: the others relocate the inversion rather than removing it, so
Phase 12's actual complaint — thin accounts being easier to flag than
fully-measured ones — survives them.

| Candidate | Rejected because |
|---|---|
| Renormalised power mean | inversion persists, and thin accounts get easier still (0.80–0.90 → 0.585–0.693) |
| `max(mean, top × f)` | `f` is an unfitted magic number; makes every account markedly easier; breaks Phase 10's additive identity *conditionally*, which is worse than breaking it consistently |
| Per-signal override | leaves the composite untouched, but elevation stops being a function of the score, which breaks ranking on `/anomalies` and makes Phase 12's PR curves describe something other than the operating decision |
| Lower the threshold for fully-measured accounts | inverts the problem by construction: it makes the composite's meaning depend on measurability, which is the thing being fixed |

---

## 3. The fix

```
composite = ( Σ over applicable signals of  wᵢ · sᵢ³ ) ^ ⅓
```

A weighted power mean of degree three, weights as `Signal` defines them, **no
renormalisation**.

### The exponent is derived, not chosen

This phase is forbidden from introducing unfitted constants, and does not. The
guarantee "any one saturated signal elevates any account" requires
`w_min^(1/p) ≥ 0.50`. With the lightest weight at 0.15:

```
p ≥ ln(0.15) / ln(0.50) = 2.74   →   p = 3
```

Three is the smallest integer that satisfies it; two leaves the lightest signal
at 0.387. Change a weight or the threshold and the derivation moves with them —
`CompositeCeilingTest` recomputes it rather than hardcoding 3.

### Dropping renormalisation is the load-bearing half

Phase 8 renormalised for a stated reason: without it "an account with only two
measurable signals could never exceed 0.45 however extreme its behaviour, and
thin-history accounts would be structurally invisible."

That was true **at degree one**. At degree three, two saturated signals reach
0.766 unaided. The power transform already does the job renormalisation was
compensating for, so keeping both compensates twice and recreates the inversion.

---

## 4. What the composite now means

Four semantic changes, each flagged rather than absorbed.

**1. It is a function of what fired, not of what fraction of the measurable
evidence fired.** An applicable-but-quiet signal and an unmeasurable one now
contribute the same nothing. Completeness lives entirely in `applicableSignals`
and `isWellEvidenced()`, which have travelled beside the score since Phase 8 for
exactly this purpose.

**2. A thin account cannot reach 1.0.** Two applicable signals, both saturated,
cap at 0.766. The composite says how much alarming evidence there is, and there
is less of it.

**3. Phase 8's documented pathology is retired.** One applicable signal,
saturated, used to report a composite of **1.00** — total certainty from a
single piece of evidence, which that phase called out and accepted. It now
reports **0.63**: elevated, and nothing like certain.

**4. Phase 10's additive identity changed form.** `sum(contribution) ==
composite` held because the parts of an arithmetic mean share its units. The
parts of a power mean sum to the composite *cubed*, so contributions are
published as **shares summing to 1.0**. The identity is still exact, it reads
better ("this signal supplied 70% of the score"), and it matches the language
Phase 10's ML attribution already used for its own drivers.

`effectiveWeight` is gone with the mean that defined it — a
weight-as-a-fraction-of-applicable-weight no longer participates in anything,
and a field that looks like it drives the score while not driving it is worse
than an absent one.

### Prior claims now inaccurate

Cross-referenced rather than duplicated:

| Where | Claim | Status |
|---|---|---|
| DETECTION_REPORT.md, "The renormalisation trade" | the composite divides by applicable weight | historical; see §3 here |
| DETECTION_REPORT.md | one applicable signal firing reads 1.0 | retired; now 0.63 |
| EXPLANATION_REPORT.md, "The identity" | `sum(contribution) == composite` | now shares summing to 1.0; see §4 |
| EXPLANATION_REPORT.md, FINDING-1 (dilution) | a signal at 1.00 can leave the composite quiet | no longer possible at saturation; dilution now needs a partially-firing signal |
| VALIDATION_REPORT.md | statistical recall 0.100 on the benchmark | measurement invalid; see §5 |
| VALIDATION_REPORT.md | "missed nine of ten anomalies it was built to catch" | caused by the benchmark, not the ceiling; see §5 |

---

## 5. Validation of the fix — and two defects in the evidence

**This section uses labels to check the fix. It does not use them to choose
anything.** No weight and no threshold moved, and the exponent was derived from
the weights before any benchmark ran. Fitting against Phase 12's label set
remains deferred; the strongest single number there was still n=4.

### Re-running Phase 12's benchmark found the benchmark was wrong

**Defect 1 — the recent window.** The bursts were created inside the loop that
built each anomalous account, and the next iteration advanced the clock by 150
hours. By scoring time, only the last of the ten still had its burst inside the
one-hour recent window. The other nine had no recent payments at all, so the
amount and burst signals reported *insufficient data* and could not fire
whatever the aggregation did.

**Defect 2 — the baseline window.** Histories were built one account at a time,
so the earliest accounts' payments fell outside the thirty-day baseline before
the last account was finished, leaving them nothing to be judged against.

Phase 12 measured those two things and attributed the result to the composite
ceiling. **The ceiling is real — it is arithmetic, provable from the weights
alone — but that benchmark never exercised it once.**

### Before and after, on identical signal outputs

With both defects corrected (bursts together at the end, histories interleaved):

| | Statistical recall | False positives |
|---|---|---|
| Phase 12's published figure | 0.100 | 0/40 |
| Corrected benchmark, **legacy** aggregation | **1.000** (10/10) | 0/40 |
| Corrected benchmark, **Phase 13** aggregation | **1.000** (10/10) | 0/40 |

**The aggregation change moves nothing on this benchmark**, because both
functions are scored from the same `SignalScore` lists and every anomaly in it
saturates three signals at once — a burst of five payments each a thousand times
the account's usual saturates amount, burst *and* velocity. Three saturated
signals clear the legacy mean comfortably. That population never met the
ceiling.

### Why no ledger-level benchmark reaches it

An account anomalous on exactly one axis has **two** applicable signals, not
five: burst needs three recent payments, and the two rate signals each need ten
transactions in the recent window. At two applicable signals the legacy mean
already gave 0.556 and flagged. Measured, on ten such accounts:

```
=== SINGLE-AXIS ANOMALIES (reachability of the ceiling) ===
  10 of 10 saturate the amount signal; 2 signals applicable each
  Phase 8 weighted mean : recall 10/10, false positives 0/20
  Phase 13 power mean   : recall 10/10, false positives 0/20
```

Reaching three-or-more applicable signals while staying single-axis needs a
**high-volume account making one out-of-character payment** — and keeping
velocity quiet at ten transactions an hour requires a baseline rate of about ten
an hour, which is roughly seven thousand payments across the thirty-day baseline
span. That is a real and important account type, and it is not one a test should
build.

So the fix is proven where it can be proven exactly — over all 31 applicable
sets, from the weights alone — and the ledger-level test records the boundary of
what a benchmark can reach. An honest "no change here, and here is why" is worth
more than a fixture contorted until the number moves.

### An aside the corrected benchmark exposed

With the anomalies now all visible *and all identically shaped*, the isolation
score's recall fell to 0.300 while the statistical layer reached 1.000 — a
reversal of Phase 12's ordering. It is the same effect Phase 12 found with its
cluster of twenty-five: ten accounts doing the same unusual thing are a
population, and a forest is built to isolate points that are few and different.
Nothing about the model changed.

---

## 6. No regression on thin history

Phase 8's design goal, and the thing a careless fix breaks. Stating it honestly
took a failing test first: the claim "no thin configuration got easier" is
**false**. One did — `amount_outlier` alongside `velocity` asked 0.900 of a lone
signal and now asks 0.794.

The true and stronger property is about the floor:

| | Cheapest single-signal score that flags anything |
|---|---|
| Legacy | **0.500** (one applicable signal) |
| Phase 13 | **0.794** (every configuration) |

Under the legacy function an account with almost no history was the *cheapest
account in the system to flag*. That floor rose by nearly sixty per cent, and it
is now identical everywhere. Thin-history accounts as a class became
substantially **harder** to flag, and the one pair that moved the other way
moved to a bar far above the old floor.

Also asserted: a signal merely over its own firing threshold still cannot flag
an account alone (0.1, 0.25, 0.5 and 0.7 all stay below), and three
partially-firing signals do not flag. Noise suppression is unchanged in
substance.

---

## 7. What this phase did not touch

- **The five signals**, their statistical bases and their thresholds (Phase 8).
- **The Isolation Forest**, its features, its training, and its attribution
  (Phases 9–10).
- **The label and validation infrastructure** (Phase 12).
- **Any weight, and the elevation threshold.** Both are exactly as Phase 8 left
  them. The ceiling finding is an argument for revisiting the weights and this
  phase does not act on it.

Determinism is preserved: the aggregation is a pure function of the signal
outputs, and `applicableSignals` / `isWellEvidenced` report exactly as before.

---

## 8. The standing caveat

Unchanged in substance, and now resting on one less broken measurement.

- **The detector is still unvalidated against reality.** No real fraud has
  passed through this ledger. This phase fixed a defect in how evidence is
  combined; it did not establish that the evidence is any good.
- **What was proven is structural**, and structural findings need no
  representative sample: the ceiling was arithmetic, its closure is arithmetic,
  and both hold for every account this system will ever score.
- **The weights remain unfitted judgement.** The exponent is derived *from*
  them, so it inherits their arbitrariness exactly: a different weighting would
  imply a different exponent, and nothing here says the current weighting is
  right.
- **One benchmark figure in a prior report was wrong**, and the error was in the
  fixture rather than the system. That is worth remembering the next time a
  measured number disagrees with an arithmetic one.

---

## Posture heading into Phase 14

The aggregation is no longer the constraint. What remains is the one this
project has deferred since Phase 8: the weights are judgement, the threshold is
convention, and the exponent is now derived from both — so all three move
together, and none of them has ever been fitted to an outcome.

Phase 12 built the machinery to fit them honestly and declined to use it on n=4.
That remains the right call, and it remains gated on the same unglamorous thing:
somebody has to label accounts.
