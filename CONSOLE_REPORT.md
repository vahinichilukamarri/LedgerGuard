# Ops Console — Phase 14

**Scope:** frontend only · **Backend files changed:** none
**Write paths added:** none, deliberately · **Tests:** 99 frontend, 433 backend unchanged
**Stack:** React 18 + TypeScript (strict) + Vite + TanStack Query + Vitest

> **Update.** §§1-10 describe Phase 14 as it shipped: a read-only, three-route
> console. The console has since grown ledger, reconciliation, simulation and
> validation pages (which do write, through the real API) and had a full visual
> redesign. **§11 is the current state**; where a statement below ("three routes",
> "no write path", "99 tests") no longer holds, §11 says what replaced it. The
> screenshots in §7 are of the Phase 14 design.

Phases 8 to 13 built a detector that is careful about what it claims. Every
report since Phase 8 has said the scores are unvalidated judgement rather than
measurement, the weights are unfitted, and each threshold is a stated convention
rather than a calibrated cut-off.

This is the first phase where any of that reaches a human who is not reading
JSON. So the question this phase had to answer is the visual form of the one
Phases 8 to 13 kept asking numerically: **does the screen claim more than the
backend does?** A console can overstate in ways an API cannot — a red badge, a
single combined score, a percentile sitting in the column next to an
attribution, a verdict with its provenance dropped for space — and each of those
would undo a decision the backend spent a phase making.

The headline is not the layout. It is that **the constraint was implemented in
component signatures rather than in review discipline**: the components that
could overstate are built so that the qualification cannot be passed separately
from the thing it qualifies, and tests assert the properties rather than the
pixels.

---

## 1. What the API actually offers, and three things it does not

Everything below follows from reading the eleven wire records rather than from
guessing, so it is worth stating what came back.

`GET /detection/anomalies` returns a **plain array** — parameters are `minScore`
and `includeMlOnly`, and that is all. No page, no sort, no offset. But each row
is already rich: both scores, the agreement enum, `applicableSignals`,
`wellEvidenced`, the **full** per-signal breakdown with Phase 13's shares, and a
digest naming each layer's drivers plus `corroborated` and
`modelDriversOutsideStatisticalView`.

Two consequences, both good:

- The entire ranking is **one request**, and **no language model is called
  anywhere in it**. That is not the console being careful; it is
  `DetectionController` generating a narrative on the detail endpoint alone, for
  the Phase 11 reason that fifty model calls to help somebody choose one account
  to open is latency and spend on the wrong request. The console is built around
  that rather than working against it.
- Sorting, filtering and paging happen **in the browser**, because the endpoint
  offers no parameters for them. The table header and the line above it say so.
  A client-side page presented as a server-side one would misdescribe what the
  reviewer is looking at, and the difference matters when someone narrows to
  eight model-only rows and needs to know eight out of what.

Three gaps. None was fixed, because this is a frontend phase.

| Gap | What it costs | What was done instead |
|---|---|---|
| **No bulk label read.** `GET /validation/labels/{accountId}` is per-account. | A review-status column is one request per row. | Bounded to the rendered page (25), cached by React Query, and the column header says "fetched per row; no bulk endpoint exists". A bulk endpoint would be cleaner and would be a backend change. |
| **No CORS mapping** in the Spring app. | A separate-origin console cannot call the API. | The Vite dev server proxies `/detection` and `/validation`, so the console is same-origin and the backend is untouched. Real deployment means same-origin assets or a CORS mapping *then* — a deployment decision, deliberately not made here. |
| **`NarrativeSource` has no third value.** | The console cannot say *why* a template was served. | Nothing invented. See §4. |

The third one deserves emphasis because the phase brief asked for it. The brief
said the UI should be able to show "narrative generation took the template path"
*if the API surfaces that*. **It does not, and that is deliberate** —
`NarrativeSource` documents the choice at length: a template served because the
model timed out is the same artefact as one served because no key is configured,
and naming the difference on the wire would turn an ordinary degradation into
something a reviewer feels they should act on.

So the console says exactly what is knowable — this is the deterministic
template — and adds that the API does not distinguish the reasons. Manufacturing
"the model call failed" from a `TEMPLATE` badge would be the console asserting
something the backend refused to assert. A test pins that the words "fell back"
and "fallback" never reach the DOM.

---

## 2. Information architecture

Three routes. Everything a reviewer can ask here is one of three questions.

```
/anomalies          the ranking          ?minScore &includeMlOnly &state* &q &sort &dir &page
/accounts/:id       one account          ?compare  #agreement #signals #attribution #narrative #labels
/model              which model scored   —
```

Filter, sort and page state lives in the **URL**, not in React state. "The
model-only rows" is the interesting subset of this system, and a link to it
should reproduce it.

```
src/
  api/         types.ts  thresholds.ts  client.ts  queries.ts  agreement.ts
  components/
    uncertainty/   AgreementChip  ScoreReadout  NarrativeBlock  LabelChip
                   PopulationContext  SignalShareBar  CaveatList  format
    anomalies/     AnomalyTable  FilterBar  ranking (pure sort/filter/page)
    account/       AttributionTable  NarrativeSection  LabelHistory
  pages/       AnomaliesPage  AccountPage  ModelPage  NotFoundPage
```

The `uncertainty/` directory is the point of the phase. Those eight components
are the only place a score, a state, a narrative, a percentile or a verdict is
allowed to reach the screen, so the constraint is enforced at eight files rather
than at every call site.

**Server state is TanStack Query and nothing else.** Phase 11 already caches
narratives server-side and the detection reads are computed per request from the
ledger, so `staleTime` is 15 seconds and no component derives a stored value
from a response. A long client cache would mean showing yesterday's composite
beside today's ledger with no way for a reviewer to tell which they had.

**Types are hand-written** from the Java records rather than generated. The
point of transcribing eleven records is that one person reads every field once
and decides what it may be rendered as — a generated client would have handed
the console `share` and `percentile` as two indistinguishable numbers, and the
comment recording that they are not is on the field declaration.

**`thresholds.ts` is the only place a comparison number lives.** A threshold
copied into a component drifts from the backend silently, and a console drawing
a line somewhere the detector does not is worse than one that draws no line.

---

## 3. The eight agreement states

`Agreement` has four values. Two of them cover pairs of situations that mean
materially different things, and **the API already publishes enough to tell them
apart** — so collapsing them would be the console discarding information the
backend went to the trouble of exposing.

| Rendered state | Condition | Why it is its own state |
|---|---|---|
| `BOTH_ELEVATED_SHARED_AXIS` | `corroborated` | real corroboration, and possibly partial |
| `BOTH_ELEVATED_UNRELATED` | `!corroborated` | **`Reconciliation` calls this the hardest case in the system**: two elevated scores on unrelated evidence, which looks like agreement in the numbers and is not |
| `ML_ONLY_DILUTED` | `corroboratedSignals` non-empty | a signal *did* fire and the composite did not carry it. **Not a disagreement**, and reporting it as one would be false |
| `ML_ONLY_OUTSIDE_VIEW` | drivers outside the statistical layer | the row Phase 9 built a parallel score to surface |
| `ML_ONLY_SAME_AXIS` | neither | the layers disagree about the same evidence |
| `STATISTICAL_ONLY` | — | unusual for this account, ordinary for the population |
| `BOTH_QUIET` | — | labelled "the weakest claim either layer can make", never "clear" |
| `NO_MODEL` | `agreement === null` | an absence of a second opinion, never a quiet one |

Nothing here is inferred beyond the published fields. Each branch restates a
condition `Reconciliation.narrate` already switches on, and the **enum the API
returned is printed literally on every chip**, so the console's finer reading
never hides the API's.

---

## 4. The uncertainty treatments, one at a time

### No risk badges, and no severity colour at all

There is **no red and no green in the stylesheet**. A palette running from calm
to alarming is a calibrated risk scale drawn in CSS, and there is no calibrated
risk scale to draw.

Hue encodes *which* agreement state a row is in and nothing else: four category
tones at matched lightness and saturation, so no state looks louder than
another. Every state is also fully readable **without colour** — each chip
carries a text qualifier, the literal enum, and a pair of pills reading
`statistical: elevated` / `model: not elevated` in words. Turn the stylesheet
off and the chip still says everything it says.

The one deviation is a single amber wash, used for exactly one thing: the
population-context blocks, which need to look unlike evidence.

### A score never appears without its convention

`ScoreReadout` has **no mode that prints the value alone.** A reader who sees
`0.62` supplies their own scale, and the scale they supply is "out of 1.00, so
fairly bad" — a calibrated reading of a number that has never been calibrated.

So it always renders:

```
0.78
at or above the 0.50 convention (not fitted) · statistical composite

0.44
below the 0.60 convention (not fitted) · isolation score, where roughly 0.50 is
the middle of the distribution
```

The two scales name **different** conventions, because 0.5 on the composite and
0.5 on the isolation score are not the same claim and `Agreement` explains at
length why one cut-off for both would be tidier and wrong. Raw signal statistics
get the same treatment against their own scale — modified z at 3.5, surprisal at
3.0 — since a reader comparing a surprisal of 3.2 against the amount signal's
3.5 would read a fired signal as a quiet one.

A missing score renders as an absence with the reason, never as `0.00`, which
would be a claim the system has not made.

### Attribution and population context cannot touch

`FeatureAttribution` carries two groups of fields. `splitsPerTree`,
`isolationBits`, `excessBits` and `share` are what the forest did to this
account's real paths. `percentile` and `median` are **not** — they are facts
about the training population, supplied because attribution can name a feature
but cannot say which direction it was extreme in.

In adjacent table cells those two groups read as one finding, and the percentile
silently acquires the model's authority. So each feature gets **two rows**: the
first is attribution and only attribution; the second is the population context
inside its own labelled, visually distinct wrapper reading *"Context —
population, not the model's reasoning"*. A test asserts no percentile renders
outside such a wrapper anywhere on the page.

All eleven features appear, including the ones that did not isolate, because
truncating to the isolating ones would let a reviewer read the rest as merely
absent — a feature that pushed the account toward the crowd is a finding.

### The narrative's source is a required prop

`NarrativeBlock` takes its source as a **required parameter, not an optional
badge**. A component that could render prose without saying what wrote it would
eventually be called that way, and the two sources are not interchangeable: one
is a deterministic restatement of the numbers below it, the other is a hosted
model's prose that passed validation.

- **LLM** — a visually distinct container plus *"Written by a hosted model. It
  restates the evidence below; it cannot add to it."*
- **TEMPLATE** — *"Generated from the numbers below by a fixed template. The API
  reports only that a template was served — it does not distinguish a template
  served because no model is configured from one served because a model call did
  not succeed, so neither does this console."*

The `?narrative=template` toggle shows both **side by side**, which is the
honest use of that parameter: a reviewer can read the model's prose against a
deterministic restatement of the same evidence and check the numbers
themselves — the only check available to someone not reading the validator's
source. When the default narrative *is* the template, the page says so, rather
than presenting one text twice as though the pair corroborated something.

### A verdict cannot be rendered without its provenance

`LabelChip` takes an `AccountLabel`, **never a verdict string.** A chip
accepting `verdict="ANOMALOUS"` could be called without provenance, and
"confirmed anomalous" with nothing attached is the exact failure Phase 12 built
three enums to prevent.

Every verdict therefore carries four qualifications, structurally inseparable:

- **Source** — human review, dispute feed (independent of this system), or
  synthetic. `SYNTHETIC` carries a standing warning that it is circular as
  evidence about detection quality and never pooled with the others.
- **Stratum** — flagged pool (dense in positives, biased) or audit pool (the
  only honest recall denominator).
- **Blindness** — an anchored verdict is partly an opinion about the detector's
  opinion and cannot be read as independent.
- **Latency** — the gap between the behaviour and the truth about it, rendered
  in days where it runs to days.

The compact list-view form is shorter but not looser: source and stratum are
abbreviated and present. A column showing only "anomalous" would let a synthetic
label and a chargeback sort as the same thing.

An unlabelled account reads *"No label recorded. Nobody has judged this account
and no dispute has arrived — which is an absence of evidence, not a verdict of
ordinary."* A failed label lookup reads "label lookup failed", because a failed
request and an unjudged account are different and only one is a fact about the
account.

### Caveats above the evidence, and a standing banner that cannot be dismissed

`AccountExplanation` keeps `caveats` outside the summary with the reason stated
in the record: a limitation folded into a paragraph is a limitation nobody
reads. A footer would reintroduce the same problem on a different axis, so the
caveat list is the **first thing on the detail view**, before a single number.

The console-wide caveat is part of the masthead on every screen and has no
dismiss control. A banner a reviewer can close says it once, to one person, on
one day.

### A copy guard borrowed from the backend

`ForbiddenVocabulary.WORDS` governs generated prose. None of it governed UI
chrome, and chrome is where overstatement is easier to write — a column header
reading "Risk" costs four characters and asserts a calibrated scale. So a test
runs the backend's list against the rendered DOM of every view, plus the
console's own additions: `high risk`, `low risk`, `risk level`, `risk score`,
`safe`, `verified`, `trustworthy`.

---

## 5. The review write path: read-only

**Decision: the console displays labels in full and writes none.** Not caution —
the argument is specific.

Phase 12's review queue is **blind by default**, and
`ReviewCandidateResponse` states why: a reviewer shown "0.87" before deciding
produces an opinion about the detector's opinion, and a detector evaluated
against anchored labels is largely measuring its own influence. `RecordLabelRequest`
makes `scoresVisible` mandatory precisely so that anchoring stays visible rather
than becoming invisible.

A console whose entire purpose is displaying scores is therefore the worst
possible host for a labelling control. **Every label submitted from the detail
page would necessarily be `scoresVisible: true`**, by construction, because the
scores are on the screen. A "mark reviewed" button beside a 0.78 would quietly
fill the label set with anchored verdicts and corrupt the metric Phase 12 exists
to protect — and it would do so while looking like a convenience.

So verdicts are recorded through `POST /validation/labels`, and the pages say so
where a write control would otherwise sit. If labelling belongs in a UI later,
it belongs in a **separate blind route that renders no scores at all**, drawing
candidates from `GET /validation/review/next?blind=true`. That is Phase 15
scope, not a button bolted onto this page.

Two tests hold the line: no control on the detail view carries writing language,
and there is no form.

---

## 6. Loading and failure, per fetch

Not a global spinner. The four queries on the detail view have very different
standing — the arithmetic is fast and final, the narrative is slow and optional,
the labels are neither — so each states its own condition.

The important case is the narrative. **Every number on the detail view comes
from the `?narrative=template` request, which never calls a model.** The default
narrative is a second, independent query with retries off. So:

- the arithmetic is on screen and final while the slowest call in the system is
  still in flight, under a *"Narrative pending — every number on this page is
  already final and will not change when it arrives"* block;
- a narrative that never returns costs prose, not evidence, and renders as
  "narrative not retrieved … this says nothing about the account" beside a
  template resting on the same evidence — never as a failed account.

Every failure is phrased as a failed fetch. "Could not load the explanation" and
"this account has no explanation" are different statements and only the first is
true.

---

## 6a. Visual redesign — a second pass on the same constraints

The first pass optimised almost entirely for one property: nothing renders that
overstates the backend. It got that right and, in doing so, produced a page
that read as a wall of qualifying text — every value carried its caveat inline,
every table cell wrapped three lines, and the whole thing looked like a debug
dump rather than a product a reviewer would want to open twice.

Those are separable problems. "Does this overstate what the backend knows" and
"can a person tell what they're looking at in five seconds" are different
questions, and a console can fail the second while acing the first. This pass
answers the second without touching the answer to the first — no fact, number,
provenance string, or caveat that reached the DOM before was removed; every
uncertainty test from §2 still passes unchanged.

**What changed, and why it doesn't weaken anything:**

- **A real design system** — a neutral surface palette, one accent colour
  (indigo) reserved for links, focus rings and active navigation, a type and
  spacing scale, and consistent cards with shadow and radius. The accent never
  appears on a score, a badge, or a table row — it marks *interactive* elements,
  not *evaluative* ones, so it carries no severity meaning to contradict §4.
- **A magnitude meter beside every score** — a thin bar showing where 0.78 sits
  on its 0–1 scale, filled in one neutral colour at every value. It is a visual
  rendering of the number already printed next to it, not a new claim: the fill
  colour does not change with magnitude, so it cannot be read as "close to red."
- **Toggle chips instead of a native `<select multiple>`** for the agreement-
  state filter. The old control needed a modifier key nobody discovers
  unprompted and looked like an OS artifact dropped into the page. The new one
  is a set of checkboxes styled as chips — same `states: DisplayState[]`
  contract, same eight options, discoverable by looking at it.
- **Two-line clamping on secondary table text**, with the full string kept as a
  `title` tooltip. A driver list or an evidence sentence that ran to three
  wrapped lines per cell was the single biggest contributor to the "wall of
  text" complaint; the text is unchanged and still in the DOM (so every test
  asserting on it is untouched) and still reachable in full on hover or on the
  account detail page — only the table's own visual footprint shrank.
- **A sticky table of contents on the account page**, linking to the same
  section ids the deep-link tests already exercise. Nothing is hidden behind a
  tab — every section still renders in full and in order — the TOC is purely
  additive, an orientation aid for a page that has six dense sections and, on
  the first pass, no way to jump between them short of scrolling.
- **A proper app shell header** — a mark, a wordmark, pill-style navigation —
  with the standing caveat moved to its own full-width strip underneath rather
  than sharing a flex row with the nav. Its text is byte-for-byte the sentence
  it was before; it just has a line of its own to be read on now.

**A real bug this pass found:** the per-signal share rows used a three-column
CSS Grid (`190px 1fr 92px`) that, on a phone-width card, left the flexible
column almost no space. A grid item's automatic minimum size is its content's
min-content size unless overridden, and a full-width explanation sentence
spanning all three columns doesn't shrink to fit — it overflows the row's own
box instead, which cascaded into roughly 45px of horizontal page overflow on
mobile. Below 640px the row now stacks as a simple flex column instead of
fighting for three fixed tracks in too little space, confirmed by measuring
`document.documentElement.scrollWidth` against `clientWidth` directly (both a
real backend field-account view and the ranking table were checked; only the
share rows were affected). Nothing about this was caught by the test suite —
jsdom doesn't lay out CSS Grid tracks — which is exactly why it was checked by
hand in an emulated mobile viewport rather than assumed clean.

All 99 tests pass unchanged after this pass; `tsc --noEmit` and `vite build`
are clean.

---

## 7. The views

Screenshots in `docs/console/`, captured headlessly against the mock API in both
themes.

| View | File |
|---|---|
| The ranking, all six states | `01-ranking.png` (dark), `12-ranking-light.png` (light) |
| Narrowed to the two model-only states | `02-ranking-model-only.png` |
| Account detail, full page | `03-detail-shared-axis.png` |
| Both elevated on unrelated evidence | `04-detail-unrelated.png` |
| Model only, diluted | `05-detail-diluted.png` |
| Model only, outside the statistical view | `06-detail-model-only.png` |
| One signal able to judge | `07-detail-thin-evidence.png` |
| Neither layer elevated | `08-detail-both-quiet.png` |
| Both narratives side by side | `09-narrative-comparison.png` |
| Model provenance | `10-model.png` |
| Two reviewers disagreeing | `11-labels-disagreement.png` |
| Explicit loading state | `13-loading-state.png` |
| Explicit failure state | `14-failure-state.png` |

**The ranking** is eight columns: rank (the order the API returned), account,
statistical composite, isolation score, how the layers relate, evidence, named
drivers per layer, review status. Each score column prints its own convention
under the header. The drivers column lists each layer's drivers under its own
layer, because merged they would read as one set of reasons produced by one
thing.

**The detail view** runs caveats → the two scores side by side → how the layers
relate → per-signal shares → attribution with its interleaved context rows →
narrative → labels. The order is an argument: the qualification comes before the
numbers.

**Not captured:** the narrative-pending state. Headless Chrome waits for the
pending request before it will shoot, so the state is covered by a test that
gates the response on a promise it resolves itself, and by verifying in a live
browser that all arithmetic renders while the narrative hangs.

### Seeing it

The console needs no database. `mock/server.mjs` serves the real wire shapes for
seven accounts covering every state, and is **not** a test double — nothing in
the test suite imports it and a change to it cannot make a test pass.

```powershell
cd console
npm install
npm run mock          # http://localhost:8080, in one terminal
npm run dev           # http://localhost:5173, in another
```

Against the real backend, run the Spring application on 8080 instead; the Vite
proxy is already pointed there. A trained model is optional — an untrained one is
a state the console renders deliberately.

---

## 8. Findings during implementation

Five things were wrong and got fixed. Three of them were in this phase's own
work, which is the useful kind to record.

**1. The mock's data contradicted itself.** Each account's narrative and its
driver lists were hand-written separately, so every row named the same three
model drivers regardless of its own attribution table — and three feature names
(`accountAgeDays`, `distinctCounterparties`, `meanInterArrivalSeconds`) **do not
exist in this system at all.** A mock whose parts disagree is worse than none,
because a screenshot from it looks authoritative. It now carries the real eleven
features with the real visibility from `FeatureProvenance`, computes shares from
excess bits, and derives the driver lists, corroboration, outside-view list and
narrative by the same rules `Reconciliation.of` uses. Both share identities were
then checked against the running server: signal shares sum to 1.0, attribution
shares sum to 1.0. The test fixtures got the real names too.

**2. A test that proved nothing.** One assertion awaited
`/deterministic template/i` — which also matches the compare checkbox's own
label, present before any fetch resolves. It passed while asserting against a
narrative that had not loaded. It now waits on copy only the rendered block
contains. This is the frontend form of the Phase 7 and Phase 11 lesson about
tests that pass for the wrong reason, and it was found by making a *different*
test fail.

**3. Deep links did not work, silently.** The first `useScrollToHash` scrolled
on the next animation frame and did nothing: while the document is still
loading, the browser's own scroll handling runs afterwards and resets the
position, so the scroll happened and was immediately undone — indistinguishable
from the effect never firing. It waits for `load` now, and six tests pin it. A
browser found this, not a test, which is the argument for having built the mock.

**4. `.th-note` only blocked inside `thead`.** In table cells the qualification
ran onto the line before it: "4 of 5well evidenced". The note attached to a
number is now always on its own line.

**5. A `<dd>` closed with `</dt>`.** Caught by the first render.

### A backend documentation discrepancy, flagged not fixed

`mvn test` reports **433 tests across all 54 test classes, 0 failures**. The
README and `COMPOSITE_CEILING_FIX_REPORT.md` both state **536**, and the
README's own per-class table sums to **538 across 56 rows** when only 54 test
classes exist.

The backend code is byte-identical to `v0.13-ceiling-fix` — `git diff` against
that tag over `src/` and `pom.xml` is empty — so this predates Phase 14 and is
an accounting question in the backend documentation, not a regression. It is
reported here rather than corrected, because editing backend test accounting in
a frontend phase would be exactly the kind of quiet scope drift the phase
constraints forbid.

---

## 9. What this phase deliberately does not do

- **No backend change of any kind.** No CORS mapping, no bulk label endpoint, no
  third `NarrativeSource` value. The three gaps are named in §1 with what each
  would cost.
- **No write path.** See §5.
- **No blended score, anywhere.** Not as a column, not as a sort key, not as a
  badge. There is no blended score in the backend and inventing one at the
  presentation layer would reintroduce precisely the conflation Phase 9 spent
  the phase avoiding.
- **No server-side paging pretence.** The endpoint has no such parameters and
  the UI says the paging is its own.
- **No accessibility audit.** Colour is never the sole encoding and controls are
  labelled, but no screen-reader testing was done and none is claimed.
- **No authentication.** The console is as open as the API it reads, which is
  wide open. Not a console problem, and not solved here.
- **No mobile layout.** Tables scroll horizontally inside their own container
  and the page never scrolls sideways, but the ranking is designed for a desk.

---

## 10. The standing caveat, in visual form

Phases 8 to 13 each closed by narrowing what the system claims. This phase adds
no claim at all — it renders existing ones — so its caveat is about the
rendering:

> **A screen is more persuasive than a number.** Everything in this console is
> the same unvalidated judgement it was in the JSON: unfitted weights, stated
> conventions rather than calibrated cut-offs, and no measurement against ground
> truth. The console is built to keep saying so at every point where a reader
> could stop reading — beside each score, on each chip, under each verdict, and
> in a banner nobody can close. But a layout is an argument about what matters,
> and a reviewer who looks at a ranking for a week will start to trust its
> order. That order is the backend's composite descending, which is a number
> nobody has validated.

The most honest thing the console does is refuse to reduce anything: two scores
that never combine, eight agreement states that never collapse to "flagged",
attribution that never blends with population context, and a verdict that cannot
be displayed without saying who reached it, from which pool, and whether they
could see the score first.

---

## 11. The console today: three layers, real charts, and a landing page

Everything in §§1-10 was written for a console with three read-only routes. That
console has since become the ops console for the whole system, and its
presentation was rebuilt. This section records the current state and what the
rebuild found. The constraints from §4 were not relaxed; they were kept, and the
new code is tested against them.

### Three layers

```
/                          landing        what the system is, one way in      (outside the app shell)
/overview                  dashboard      live totals and charts              (inside the shell)
/ledger/accounts           functional     list accounts
/ledger/accounts/new       functional     create an account
/ledger/payments           functional     create a payment, then refund it
/ledger/transactions       functional     list transactions, view postings, reverse one
/reconciliation            functional     run history, run a reconciliation, triage incidents
/reconciliation/incidents/:id             both sides of one discrepancy
/anomalies                 functional     the ranking (unchanged from Phase 14)
/accounts/:id              functional     one account's assessment (unchanged)
/model                     functional     which model scored, and train/retrain it
/validation                functional     blind review queue, census, precision/recall report
/simulation                admin          inject settlement faults and disputes
```

The landing page is deliberately **outside** the shell: a first-time visitor meets
the product before its navigation, and the landing page renders no live data, so it
cannot show a number that has gone stale. Every other route lives inside the
sidebar shell, whose standing caveat strip (§10) is unchanged and still cannot be
closed.

**Write paths now exist**, so "none, deliberately" in the header no longer holds
for the console as a whole. They go through the real API and are limited to:
create account, create payment (with an `Idempotency-Key`), refund, reversal, run
reconciliation, resolve incident, record a validation label, train the model, and
the two admin injections on `/simulation`. The one the original phase refused is
still handled the way §5 argued: labels are blind by default and are recorded as
unanchored unless the reviewer chooses to see the scores first. `/simulation`
manipulates a simulated processor, is walled off in the sidebar with its own warm
tone, and asks for confirmation before anything fires.

### The dashboard (`/overview`)

No summary endpoint exists, so the page composes six independent reads
(`/accounts`, `/transactions`, open incidents, reconciliation runs,
`/detection/anomalies`, `/detection/model`). Each figure is accurate as of its own
request and two can be a moment apart; the page says so. Nothing on it is a
placeholder. Where a chart needs a time series it is built from timestamps the
records already carry:

| Mark | Source | Note |
|---|---|---|
| Stat cards (accounts, transactions, open incidents, latest match rate) | the reads above | count up to the exact value; sparklines are decoration |
| Ledger activity (area) | `createdAt` of the newest 100 transactions | cumulative, anchored so the last point is the true total even when only one page was fetched |
| Open incidents by severity (donut) | open incidents | the one place severity colour is used, because the backend computes the severity |
| Reconciliation runs (columns) | last 8 runs | matched vs discrepancies; indigo and the caveat tone, not red or green |
| Detection agreement (donut) | `explanation.agreement` per scored account | category hues, never severity |
| Statistical composite and isolation score (two histograms) | `statisticalScore`, `ml.score` | **two separate charts, on purpose**: one combined distribution would be the blended score §4 forbids |

All charts are hand-written SVG in `components/charts/` (`Donut`, `AreaChart`,
`ColumnChart`, `Sparkline`, `useTip`). No charting library was added. Each has a
hover or focus readout and an `aria-label` that states what is plotted; bars are
capped at 26px and rounded only at the free end, and a legend appears whenever
there are two or more series.

### Presentation

- **Two stylesheets.** `styles.css` still owns the tokens: neutral surfaces, the
  single indigo accent, the four category hues, and the severity scale scoped to
  reconciliation. `polish.css` is loaded after it and changes how those tokens are
  *used* (type, depth, motion, charts, the landing page). It introduces no colour of
  its own, so it cannot break the palette rules from there.
- **Motion** is CSS keyframes plus an `IntersectionObserver`
  (`components/motion/`: `Reveal`, `CountUp`, `useInView`, `useTheme`). It is
  decoration only: every animated figure ends on exactly the number the data holds,
  everything collapses under `prefers-reduced-motion`, and the hooks return the final
  state immediately under the test runner so no test waits on a tween.
- **Light and dark** follow the OS by default, with a toggle in the sidebar and the
  landing nav that stores the choice. The dark token block is declared under both the
  media query and `[data-theme="dark"]`, with a `:not([data-theme="light"])` guard so
  an explicit light choice beats an OS-dark setting.
- **Loading** is a shimmering skeleton (`Pending`) in place of a line of text; the
  words "Loading ..." are still in the DOM for screen readers and tests.
- Inter is loaded from Google Fonts by one `<link>` in `index.html`, falling back to
  the system font stack. Remove that link for zero external requests.
- No dependency was added; `package.json` is unchanged by the redesign.

### The model page

`/model` now shows the seed, trees, sub-sample size, training accounts, the two
snapshot timestamps, a meter of the training population against the minimum, and
the 11 feature names, with a **Train / Retrain** button. Training stays explicit,
never a side effect of a read. The backend refuses to train below **32 accounts**
(`insufficient_training_data`), which is why a fresh database shows "no model
trained" and why the demo data has more than that.

### Findings from rebuilding it against the real backend

1. **The model endpoint's field names differ from the rest of the API.**
   `GET /detection/model` returns `treeCount` and `trainingSampleSize` (plus
   `featureNames`); the model summary embedded in every scored response uses `trees`
   and `trainingAccounts`. The console read the latter everywhere, so the Model page
   would have rendered blanks against a real backend. The mock server used the
   embedded names, which is exactly why it was never caught. It is absorbed at the API
   boundary in `api/client.ts` (`normaliseModel`), so pages see one `ModelInfo`.
2. **A CSS selector bug hid in the stylesheet.** The table rules were written as comma
   lists (`table.ranking, table.data th`), so the bare `table.ranking` matched and
   `table.ranking th/td` did not. The ranking and attribution tables had lost their
   cell padding and header styling. Fixed with `:is(table.ranking, table.data) ...`.
3. **Four selects had default browser styling.** The `.toolbar` rule covered only
   number and search inputs, so the reconciliation incident filters and the validation
   stratum picker were unstyled. A DOM audit of computed styles across every route now
   finds none.
4. **A "not quotable" banner used the red error style.** It is a caveat about a
   report, not an error and not a severity, so it moved to the caveat tone.
5. **`useInView` never attached to a chart whose data arrived late.** Charts render a
   placeholder while loading, so the observed node changes; an object ref stayed
   pointed at the first, absent node and the chart never animated in. It now returns a
   callback ref.
6. **The count-up could flash "-0".** A frame timestamp can precede the captured start
   time, making the eased value a tiny negative. Both ends are clamped.
7. **The Vite proxy shares a prefix with SPA routes.** `/reconciliation`,
   `/validation` and `/accounts` are both pages and API paths, so a direct load of one
   was forwarded to Spring and 404ed. The proxy entries bypass any request that
   accepts `text/html`.

### Verifying it

Run against the real stack, with data in it:

```powershell
docker compose up -d
mvn spring-boot:run          # http://localhost:8080
cd console
npm ci
npm run dev                  # http://localhost:5173
```

The mock server (`npm run mock`) is still available for reviewing without a
database, but it is not what the console was checked against. The seed used for the
redesign was created entirely through the API: dozens of accounts and payments, a
refund, a reversal, a dropped settlement record and a reconciliation run that filed
one open incident, then a trained model.

- **Tests:** 128 frontend (up from 99) pass, with `tsc --noEmit` and `vite build`
  clean. The uncertainty properties from §4 still pass unchanged. The backend suite
  was not touched by the redesign.
- **Layout:** every route was loaded at 1280px and 1440px and measured for horizontal
  overflow and for form controls left at browser defaults; none were found. Jsdom does
  not lay out CSS, so this was done in a real browser, which is also how the bugs
  above were found.
- **Not verified:** there is no visual-regression suite, and small-screen layouts were
  checked less thoroughly than desktop. The screenshots in §7 predate the redesign.

---

## Posture heading into Phase 15

The console is read-only and complete for what the current endpoints expose. The
two things Phase 15 could sensibly pick up, both already argued for above:

1. **A blind labelling route** — no scores rendered, candidates from
   `GET /validation/review/next?blind=true`, writing to `POST /validation/labels`
   with `scoresVisible: false` truthfully. This is the write path Phase 14
   declined, in the only form that does not corrupt the metric.
2. **A bulk label read**, which would turn the review-status column from one
   request per row into one per page. A backend change, needing confirmation.
