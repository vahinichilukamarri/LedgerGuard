/**
 * The account's caveats, above the evidence rather than below it.
 *
 * `AccountExplanation` keeps `caveats` beside the summary rather than inside it,
 * with the reason stated in the record: "a limitation folded into a paragraph is
 * a limitation nobody reads." Rendering that list as a footer would reintroduce
 * the same problem in a different axis — a limitation below the fold is a
 * limitation nobody scrolls to.
 *
 * So it sits at the top of the detail view, before a single number.
 */
export function CaveatList({ caveats }: { caveats: string[] }) {
  if (caveats.length === 0) {
    return null;
  }
  return (
    <section className="caveats" aria-labelledby="caveats-heading">
      <h2 id="caveats-heading">What this page does not know</h2>
      <ul>
        {caveats.map((caveat) => (
          <li key={caveat}>{caveat}</li>
        ))}
      </ul>
    </section>
  );
}

/**
 * The console-wide caveat, present on every screen and not dismissible.
 *
 * Every backend report since Phase 8 has said the scores are unvalidated
 * judgement. A banner a reviewer can close says it once, to one person, on one
 * day. This one is part of the masthead.
 */
export function StandingCaveat() {
  return (
    <p className="standing-caveat" role="note">
      <strong>Read-only, and unvalidated.</strong> Nothing here is a finding. The statistical
      weights are unfitted judgement rather than learned parameters, every threshold on this site is
      a stated convention rather than a calibrated cut-off, and neither score has been measured
      against ground truth — so precision and recall are unmeasured rather than approximately known.
      An elevated score means the detector found the behaviour unusual, which is not a claim about
      the account.
    </p>
  );
}
