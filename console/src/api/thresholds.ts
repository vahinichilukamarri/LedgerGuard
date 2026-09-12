/**
 * Every number the console is allowed to compare a score against.
 *
 * One module, because a threshold copied into a component is a threshold that
 * drifts from the backend silently, and a UI that draws a line in a different
 * place from the detector is worse than a UI that draws no line at all.
 *
 * All four are **conventions, not fitted values**. `Agreement.java` and
 * `Signal.java` say so at length, and the console repeats it beside every one of
 * them rather than only in a footnote.
 */

/** `Agreement.STATISTICAL_ELEVATED`. */
export const STATISTICAL_ELEVATED = 0.5;

/**
 * `Agreement.ML_ELEVATED`. Higher than the statistical cut-off on purpose: an
 * isolation score is concentrated around 0.5 by construction, so 0.5 there would
 * call half the population elevated.
 */
export const ML_ELEVATED = 0.6;

/** `Signal.AMOUNT_OUTLIER` — the Iglewicz–Hoaglin convention for a MAD-based score. */
export const MODIFIED_Z_THRESHOLD = 3.5;

/** The four count/rate signals — the upper 0.1% of what the baseline predicts. */
export const SURPRISAL_THRESHOLD = 3.0;

/** Which raw scale a signal's `statistic` is on, so the readout names the right convention. */
export function conventionFor(signal: string): { threshold: number; scale: string } {
  return signal === 'amount_outlier'
    ? { threshold: MODIFIED_Z_THRESHOLD, scale: 'modified z' }
    : { threshold: SURPRISAL_THRESHOLD, scale: 'surprisal' };
}

export function isStatisticallyElevated(composite: number): boolean {
  return composite >= STATISTICAL_ELEVATED;
}

export function isModelElevated(score: number): boolean {
  return score >= ML_ELEVATED;
}
