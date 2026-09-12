/**
 * DOM assertions that need to ask "what is rendered *outside* this element".
 *
 * Always measured on a clone. Detaching a node React still owns corrupts its
 * commit, and a test that did that would fail for a reason unrelated to the
 * claim it makes — which is the frontend version of the Phase 7 and Phase 11
 * lesson about tests that pass, or fail, for the wrong reason.
 */

/** Everything the element renders except what sits inside a PopulationContext wrapper. */
export function textOutsidePopulationContext(element: HTMLElement): string {
  const clone = element.cloneNode(true) as HTMLElement;
  clone.querySelectorAll('[data-population-context="true"]').forEach((node) => node.remove());
  return clone.textContent ?? '';
}

/** Everything the element renders except what sits inside the given selector. */
export function textOutside(element: HTMLElement, selector: string): string {
  const clone = element.cloneNode(true) as HTMLElement;
  clone.querySelectorAll(selector).forEach((node) => node.remove());
  return clone.textContent ?? '';
}

/**
 * The vocabulary the backend forbids its own narratives from using, mirrored.
 *
 * `ForbiddenVocabulary.WORDS` governs generated prose. None of it governed UI
 * chrome until this phase, and chrome is where the overstatement would be easier
 * to write: a column header reading "Risk" costs four characters and asserts a
 * calibrated scale the system does not have.
 *
 * The additions below are the console's own. "Risk", "safe" and "clean" are the
 * three a dashboard reaches for by default, and each is a claim about the
 * account rather than about what the detector measured.
 */
export const FORBIDDEN_COPY = [
  // ForbiddenVocabulary.WORDS, verbatim.
  'fraud',
  'fraudulent',
  'criminal',
  'illegal',
  'launder',
  'suspicious',
  'malicious',
  'guilty',
  'confirmed',
  'proves',
  'proven',
  'certainly',
  'definitely',
  'undoubtedly',
  'clearly indicates',
  // This console's additions, for chrome rather than prose.
  'high risk',
  'low risk',
  'risk level',
  'risk score',
  'safe',
  'verified',
  'trustworthy',
] as const;

/**
 * The first forbidden word in a piece of rendered copy, if any.
 *
 * Word-boundary matched, so "unclear" does not trip "clear" and
 * "classification" does not trip "class". Multi-word entries are matched as
 * phrases.
 */
export function firstForbidden(text: string): string | undefined {
  const lowered = text.toLowerCase();
  return FORBIDDEN_COPY.find((word) =>
    new RegExp(`\\b${word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(lowered),
  );
}
