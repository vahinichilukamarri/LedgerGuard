# Detection Report — Phase 8 (Statistical)

**Signals:** 5 · **Tests:** 65 · **Defects found in Phases 1–7:** none
**Calibration findings during implementation:** 2 (both recorded below)

This phase builds the statistical half of the detect layer. It is deliberately
**not** machine learning: no model, no training, no fitted parameters. What it
provides is a set of signals with defensible statistical foundations, each
producing a score rather than a flag, so that a later Isolation Forest service
has something principled to sit on top of.

---

## How to run it

From PowerShell, in the repository root:

```powershell
mvn test -D"test=SignalUnitTest,AnomalyScorerTest,RobustStatisticsTest,DiscreteTailsTest"
```

That is the 58 tests that need no Docker and finish in about a second. The
integration test needs a container:

```powershell
mvn test -D"test=DetectionFlowIntegrationTest"
```

> **PowerShell note:** quote the argument as `-D"test=..."`. Unquoted, PowerShell
> parses the `=` as an operator and expands any `*` before Maven sees it. The
> quoted form also works in `cmd.exe`; in Git Bash use `-Dtest='...'`.

Demoing it against a running instance:

```powershell
curl.exe -s "http://localhost:8080/detection/accounts/<ACCOUNT_ID>"
```

```powershell
curl.exe -s "http://localhost:8080/detection/anomalies?minScore=0"
```

> Use `curl.exe`, not `curl` — in PowerShell, `curl` is an alias for
> `Invoke-WebRequest`, which takes different arguments entirely.

---

## The two statistical cores

Five signals, but only two pieces of underlying machinery. That was the goal:
five ad-hoc rules would each need arguing about separately, whereas two cores
can be argued about once and tested properly.

### Core A — robust location and scale, for amounts

Not a z-score. **The thing being hunted is the thing that breaks a z-score.**
`(x − mean)/sd` computes both terms from a sample that contains the outlier, so
one large payment drags the mean toward itself and inflates the standard
deviation — often by enough that the payment fails to exceed its own threshold.
The effect is called masking and it worsens as samples shrink, which is exactly
the regime a per-account payment history lives in.

So: median for location, **median absolute deviation** for scale, multiplied by
1.4826 to make it comparable with a standard deviation. The flagging point is
the Iglewicz–Hoaglin convention of **3.5**. Both estimators have a 50% breakdown
point: half the sample can be arbitrarily corrupted before either moves.

**The fallback chain, which is not an edge case.** MAD is zero whenever more
than half a sample shares one value, and in a payments ledger that is ordinary —
subscriptions, fixed fees, repeated transfers. A naive implementation divides by
zero here and reports every account with a regular payment amount as infinitely
anomalous. The chain is:

| Condition | Scale used | Why |
|---|---|---|
| MAD > 0 | `1.4826 × MAD` | the robust estimate, the intended path |
| MAD = 0, sample not constant | `1.2533 × mean absolute deviation` | less robust, but only engages once the robust estimate has collapsed, and still beats a standard deviation by being linear in the deviations rather than quadratic |
| every value identical | none — degenerate | there is no scale to estimate. An identical value scores **exactly 0**; a differing one scores a **bounded ceiling**, never infinity |

The degenerate branch has dedicated tests at both the statistics and signal
levels, because it is the part most likely to be silently broken by a later
refactor.

### Core B — exact discrete tails, for counts and rates

Not a z-score either. `(k − λ)/√λ` assumes the Poisson is near-normal, which is
true for large λ and badly false for small — and small is the common case per
account. A worked example, which is also a test:

> An account averaging **0.2 payments an hour** makes **3** in one hour.
> The normal approximation gives `(3 − 0.2)/√0.2 = 6.26` — apparently
> extraordinary. The exact answer is `P(X ≥ 3 | λ=0.2) = 0.001148`, a surprisal
> of **2.94**, which sits *below* the flagging threshold.

The approximation would have raised an alert the evidence does not support, and
would do so for every quiet account that had a mildly busy hour. The exact tail
costs a short loop.

Tails are reported as **surprisal**, `−log₁₀ p`: one in a thousand becomes 3, one
in a hundred million becomes 8. That puts four signals on one linear scale and
makes the threshold a round number instead of a string of zeroes. The flagging
point is **surprisal ≥ 3**, i.e. `p < 0.001`.

Baseline rates are **Laplace-smoothed** (`+1/+2`). Without it, a population that
has never produced an event makes the first one infinitely improbable and the
signal saturates on a single observation. Smoothing says the honest thing: never
having seen one is evidence they are rare, not proof they are impossible.

---

## The five signals

| # | Signal | Asks | Statistic | Fires at | Minimum data |
|---|---|---|---|---|---|
| S1 | **Amount outlier** | Is a recent payment far from what this account normally sends? | modified z vs. median/MAD of prior payments; worst payment in the window | \|z\| ≥ 3.5, saturating at 10 | 8 prior payments |
| S2 | **Velocity** | Is it transacting faster than its own rate? | `P(X ≥ k \| λ·W)`, Poisson | surprisal ≥ 3, saturating at 8 | 20 baseline payments |
| S3 | **Burst** | Is recent activity clustered more tightly than its rate explains? | scan of the tightest k-window, Bonferroni-corrected | surprisal ≥ 3, saturating at 8 | 20 baseline, ≥ 3 in window |
| S4 | **Reconciliation mismatch rate** | Are its transactions failing reconciliation unusually often? | `P(X ≥ k \| n, p₀)`, binomial | surprisal ≥ 3 | 10 transactions |
| S5 | **Refund/reversal rate** | Are its payments coming straight back unusually often? | binomial, same machinery | surprisal ≥ 3 | 10 payments |

### Details worth stating

**S1 is two-sided.** Magnitude, not signed value, so an unusually *small* payment
scores as highly as a large one. A run of trivial amounts against a
normally-substantial account is what card testing looks like, and a one-sided
test would miss it entirely. There is no absolute amount threshold anywhere —
everything is relative to the account's own history.

**S2 and S3 are not the same signal.** Ten payments spread evenly across an hour
and ten inside three seconds have the *same hourly count*, so velocity cannot
distinguish them. Conversely an account making three payments in one second has
not raised its hourly rate, so velocity stays quiet. Velocity asks *how many*;
burst asks *how tightly packed*.

**S3's correction is load-bearing.** Searching for the most improbable window is
a multiple comparison. Any Poisson process contains runs that look tight in
isolation; scan a few hundred candidate windows and one will always clear a fixed
threshold. An uncorrected scan statistic is not a burst detector, it is a random
number generator with an alarming name. The smallest probability found is
Bonferroni-corrected by the number of windows examined. Bonferroni is
*conservative* here, since the windows overlap heavily and are nothing like
independent — and conservative is the right direction to err for a burst
detector, because one that cries wolf gets switched off within a week, at which
point its sensitivity stops mattering.

**S4 carries weight out of proportion to its precision, on purpose.** Every other
signal is the ledger describing itself. S4 is not: a reconciliation incident
means an *independent* external record disagreed about money that was supposed to
have moved. A ledger can be internally perfect and still be wrong, and this is
the only signal positioned to notice. It is also the coarsest signal here, and
the weight reflects the independence of the evidence rather than the sharpness of
the measurement.

**S4 does not re-derive anything.** It reads `reconciliation_incidents` as the
reconciler already wrote them. Re-classifying discrepancies would mean two
implementations of one judgement drifting apart, and the reconciler's is the one
with six documented outcome types and a test suite behind it.

**Window membership is decided by the transaction's timestamp, not the
incident's.** Reconciliation runs after the fact, sometimes long after; judging a
transaction by when somebody happened to notice it would make an account's
mismatch rate depend on the reconciliation schedule.

---

## Scoring and combination

Each raw statistic maps onto `[0,1]` by a **linear ramp from threshold to
saturation**. Saturating is not cosmetic: a modified z of 1000, or a surprisal of
40, would otherwise dominate every weighted sum it appeared in and reduce the
composite to a single-signal score. A logistic would look more sophisticated, but
its parameters would be less interpretable and there is nothing to calibrate them
against.

The composite is a **weighted sum, renormalised over the signals that had enough
data to judge**.

| Signal | Weight |
|---|---|
| Amount outlier | 0.25 |
| Velocity | 0.20 |
| Burst | 0.20 |
| Reconciliation mismatch rate | 0.20 |
| Refund/reversal rate | 0.15 |

> **These weights are unfitted judgment, not learned parameters.** There is no
> labelled data in this phase, so nothing here has been validated against known
> fraud. They encode two opinions worth stating: that the reconciliation signal
> deserves standing because it is the only one grounded in an independent
> external record rather than in the ledger describing itself, and that the
> amount signal deserves the most because it is the only one that points at a
> specific transaction rather than at an account. Fitting them is the ML layer's
> job.

### Three signal states, not two

A signal can fire, can look and find nothing, or can **have nothing to judge
with** — and the third is not the second. An account with four payments has not
been found innocent by the amount signal; the signal has no baseline. Collapsing
those two into "score 0" is how a detection layer quietly reports that brand-new
accounts are the safest ones on the system. `applicable` is therefore carried
separately, and every state carries an explanation.

### The renormalisation trade

> **Superseded in Phase 13.** The composite no longer renormalises over the applicable signals, and a lone applicable signal no longer reads 1.0. Phase 12 measured what renormalisation cost and Phase 13 replaced the aggregation with an unrenormalised power mean of degree three. The reasoning below is why the original choice was made, and is kept because the argument it makes at degree one is correct. See [COMPOSITE_CEILING_FIX_REPORT.md](COMPOSITE_CEILING_FIX_REPORT.md).


Dividing by the applicable weight rather than the total is a real trade-off, made
in the open. Without it, an account with two measurable signals could never
exceed 0.45 however extreme its behaviour, and thin-history accounts — where a
good deal of fraud lives — would be structurally invisible. With it, one signal
firing hard on a new account reads 1.0 on a single piece of evidence.

So `applicableSignals` travels beside the score rather than being folded into it,
and `wellEvidenced` names the floor. Quietly damping thin scores by a confidence
factor would have buried the same weakness inside a number that looked more
trustworthy.

---

## Data plumbing

**Query-time, no persisted state, no new write path.** Every signal is an indexed
read; the layer is `@Transactional(readOnly = true)` throughout.

A per-account rolling-statistics table would make scoring cheaper and is what
most systems reach for. It is absent for the same reason account balances are
derived from postings rather than stored: a cached aggregate can disagree with
the rows it summarises, and **a detector that disagrees with the ledger is worse
than no detector**, because it produces alerts nobody can reproduce from the
data. If scoring ever becomes too slow to do live, the answer is a materialised
view the database keeps honest, not application code maintaining a second copy of
the truth.

`V7__detection_indexes.sql` adds two indexes and no columns:
`payments(source_account_id, created_at)` and
`payments(destination_account_id, created_at)`. Composite on `(account, time)`
because every detection query is "this account's payments, in time order, within
a window" — the timestamp belongs in the index, and with it the window is a range
scan and the ordering is free.

---

## Calibration findings

Neither is a defect in Phases 1–7. Both were found while implementing this phase
and are recorded because the numbers changed as a result.

### FINDING-1 — a pair of payments is not a cluster

Working through the burst arithmetic *before* writing its tests showed the signal
firing on evidence that does not support it. Two payments half an hour apart,
from an account averaging one a day, scored **3.66** under a Poisson model and
would have flagged.

That is not a burst by any useful definition. Every history has a shortest gap
between some pair of events — scoring runs of two means firing on the tightest
pair in any sample at all, which is a property of having data rather than of
behaving unusually.

**Fix:** a cluster requires at least **three** payments. The reasoning sits next
to the constant, and a boundary test pins both sides: two payments are reported
as not judgeable, three tightly packed ones fire.

### FINDING-2 — a javadoc figure that the arithmetic disagreed with

`VelocitySignal` documented the worked example's surprisal as 2.96. Computing it
exactly in a test gave **2.940**. The conclusion the example draws is unchanged —
it still falls below the flagging threshold, which is the whole point of the
example — but a documented number that is wrong is a documented number nobody
should trust. Corrected, and the test now asserts the value the javadoc quotes.

---

## False-positive and false-negative posture

Qualitative, on synthetic fixtures. **There is no live production data to
calibrate against, so nothing below is a measured rate** — these are the failure
modes the design implies and the fixtures demonstrate.

### What controls false positives

| Control | Effect |
|---|---|
| **Minimum sample sizes** | the primary control, and more important than the thresholds. A threshold decides how extreme an observation must be; a minimum decides whether there is enough evidence to have an opinion. Three payments always contain a largest one |
| Bonferroni on the burst scan | without it, any Poisson process produces a "burst" |
| Cluster minimum of three | see FINDING-1 |
| Laplace smoothing | keeps a first-ever event from saturating a signal |
| Saturation | keeps one extreme statistic from dominating the composite |
| Three signal states | "no data" cannot masquerade as "looks fine" |

On the fixtures, a steady account with thirty days of history and one more
in-keeping payment scores **below 0.2 with no signal firing**, while a burst of
six payments at two hundred times the usual amount scores **above 0.6 with three
signals firing independently**. That separation is what the layer is for.

### Known false-positive modes

1. **Burst is aggressive for low-rate accounts, and this is the big one.** The
   Poisson model assumes independent arrivals. Real payment behaviour is
   over-dispersed — people do several things in one session — so genuine
   clustering is more common than the model expects. For an account averaging one
   payment a day, three payments within forty minutes scores around 5.4 and
   fires. The non-anomaly fixture for this signal had to use a *busy* account,
   which is itself the evidence. A negative-binomial or fitted over-dispersed
   model would address it; that needs data, and belongs in the ML phase.
2. **Thin evidence reads as confident.** One applicable signal firing hard gives
   a composite of 1.0. Mitigated by exposing `applicableSignals` and
   `wellEvidenced`, not eliminated.
3. **A global baseline is thin evidence about a particular account.** S4 and S5
   compare against ledger-wide rates, so an account legitimately operating in a
   higher-risk segment looks anomalous against the population average.

### Known false-negative modes

1. **New accounts are invisible.** Every signal declines to judge below its
   minimum, so an attacker using a freshly created account is unscored. This is a
   deliberate trade — the alternative is flagging every new account — and it is a
   real gap, not a solved problem.
2. **No cross-account correlation.** Every signal is per-account. A fan-out
   across many accounts, each behaving unremarkably on its own, is invisible to
   all five. This is probably the most significant structural limitation of the
   phase.
3. **Slow drift is absorbed.** With a 200-payment cap and no decay, behaviour
   that shifts gradually is taken into the baseline rather than flagged.
4. **Saturation loses ordering at the top.** Above the saturation point, "bad"
   and "catastrophic" score identically. Fine for triage, useless for ranking the
   worst cases against each other.
5. **Refunds and reversals are counted together.** An account with an unusual
   *mix* — all reversals, no refunds — is not distinguished from one with a
   normal mix at the same rate.

---

## What this phase deliberately does not do

Stated plainly rather than left to be inferred, so the statistical layer is not
mistaken for a more complete thing than it is.

- **No exponential decay**, despite being an obvious refinement. Decay needs a
  half-life; fitting one needs labelled data this phase does not have. An
  unfitted decay silently reweights history by an arbitrary constant while
  looking principled. A hard cap at 200 payments is at least honest about being a
  cap. Deferred to the ML phase, where it can be tuned.
- **No per-account baseline for S4 and S5.** Per-account rates cannot be
  estimated from the data available — most accounts have never had a mismatch, so
  their historical rate is exactly zero and any comparison against zero makes the
  first one look impossible. A genuinely per-account baseline needs far more
  history or a hierarchical model. Deferred.
- **No ML.** No Isolation Forest, no model, no training, no fitted parameters.
  That is the next phase, and building half of it here without data would be the
  expensive kind of premature.
- **No alerting, no persistence of scores, no case management.** Scores are
  computed on demand and returned. Nothing is stored, so nothing can go stale.

---

## Posture heading into the ML layer

What this phase hands over:

- Five signals with stated statistical foundations, each independently testable
  and each explaining itself in words alongside its number.
- A scoring interface that already produces continuous scores rather than flags,
  so an ensemble can consume them directly.
- A clean feature surface: `AccountActivity` is a pure data snapshot, which is
  what a feature vector wants to be.
- An explicit list, above, of what is unfitted — the weights, the absence of
  decay, the global baselines — so the ML layer knows exactly which knobs exist
  and which of them nobody has yet turned with evidence.

What it does not hand over is any claim to be calibrated. Every threshold here is
a convention from the statistical literature rather than a number fitted to this
system's data, and the weights are judgment. The layer is demoable and defensible
on its own terms; it is not tuned, and it should not be described as if it were.
