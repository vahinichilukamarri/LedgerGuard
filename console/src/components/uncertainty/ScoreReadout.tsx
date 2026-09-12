import { ML_ELEVATED, STATISTICAL_ELEVATED } from '../../api/thresholds';
import { score as formatScore } from './format';

/**
 * A score, and the convention it is being compared against.
 *
 * <h2>Why a bare number is not offered</h2>
 *
 * This component has no mode that prints the value alone. A reader who sees
 * "0.62" supplies their own scale, and the scale they supply is almost always
 * "out of 1.00, so fairly bad" — which is a calibrated reading of a number that
 * has never been calibrated. Printing the convention beside it costs one line
 * and replaces an invented scale with the real one.
 *
 * <h2>The two scales are not the same scale</h2>
 *
 * 0.5 on the composite and 0.5 on the isolation score mean different things, and
 * `Agreement.java` explains at length why using one cut-off for both would be
 * tidier and wrong. So the readout names its own convention rather than a shared
 * one, and says "convention, not fitted" every time rather than once in a
 * footnote.
 */
export function ScoreReadout({
  value,
  kind,
  absentReason,
}: {
  value: number | null;
  kind: 'statistical' | 'model';
  /** Why there is no score. Rendered instead of a zero, which would be a claim. */
  absentReason?: string | null;
}) {
  if (value === null || value === undefined) {
    return (
      <span className="score-absent" data-testid={`score-absent-${kind}`}>
        No score.{' '}
        {absentReason ?? 'This is an absence of a measurement, not a measurement of zero.'}
      </span>
    );
  }

  const threshold = kind === 'statistical' ? STATISTICAL_ELEVATED : ML_ELEVATED;
  const elevated = value >= threshold;
  const scaleNote =
    kind === 'statistical'
      ? 'statistical composite'
      : 'isolation score, where roughly 0.50 is the middle of the distribution';

  return (
    <span className="score" data-testid={`score-${kind}`}>
      <span className="score-value">{formatScore(value)}</span>
      <span className="score-against">
        {elevated ? 'at or above' : 'below'} the {formatScore(threshold)} convention (not fitted) ·{' '}
        {scaleNote}
      </span>
    </span>
  );
}

/**
 * A raw signal statistic against the threshold its own scale uses.
 *
 * Modified z flags at 3.5 and surprisal at 3, and a reviewer comparing a
 * surprisal of 3.2 against the amount signal's 3.5 would read a fired signal as
 * a quiet one. So the scale travels with the number.
 */
export function StatisticReadout({
  value,
  threshold,
  scale,
}: {
  value: number | null;
  threshold: number;
  scale: string;
}) {
  if (value === null) {
    return (
      <span className="score-absent">
        Not measurable — this signal had nothing to measure for this account.
      </span>
    );
  }
  const fired = Math.abs(value) >= threshold;
  return (
    <span className="score">
      <span className="score-value">{value.toFixed(2)}</span>
      <span className="score-against">
        {scale}, {fired ? 'at or above' : 'below'} its {threshold.toFixed(1)} flagging point
      </span>
    </span>
  );
}
