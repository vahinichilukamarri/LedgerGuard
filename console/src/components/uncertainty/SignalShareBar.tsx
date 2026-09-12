import type { SignalContribution } from '../../api/types';
import { conventionFor } from '../../api/thresholds';
import { humanise, share as formatShare } from './format';
import { StatisticReadout } from './ScoreReadout';

/**
 * One signal's share of the composite.
 *
 * <h2>A share, not a score, and labelled as one</h2>
 *
 * Since Phase 13 `contribution` is this signal's fraction of the total, in
 * [0,1], and the five of them sum to one. That is a different quantity from
 * `score`, which is the signal's own normalised extremity, and from `weight`,
 * which is the fixed declaration weight. Three numbers in [0,1] side by side is
 * how a reader ends up adding two of them together, so each is named where it
 * appears rather than left to the column header.
 *
 * <h2>Silent signals are drawn, not dropped</h2>
 *
 * A signal that could not judge renders as a row with no bar and the reason it
 * could not. Dropping it would let a reviewer infer that the behaviour was
 * absent, when what is absent is the evidence.
 */
export function SignalShareBar({ contribution }: { contribution: SignalContribution }) {
  const convention = conventionFor(contribution.signal);
  const percent = Math.max(0, Math.min(1, contribution.contribution)) * 100;

  return (
    <div className="share-row" data-signal={contribution.signal}>
      <span>{humanise(contribution.signal)}</span>

      {contribution.applicable ? (
        <span className="share-track" role="img" aria-label={`${formatShare(contribution.contribution)} of the score`}>
          <span className="share-fill" style={{ width: `${percent}%` }} />
        </span>
      ) : (
        <span className="share-silent">
          Could not judge — too little history. An absence of evidence, not evidence of absence.
        </span>
      )}

      <span className="score-against">
        {contribution.applicable
          ? `${formatShare(contribution.contribution)} of the score`
          : 'no share'}
      </span>

      <div style={{ gridColumn: '1 / -1', paddingLeft: 2 }}>
        <span className="score-against">{contribution.explanation}</span>
        {contribution.applicable && (
          <div style={{ marginTop: 4 }}>
            <StatisticReadout
              value={contribution.statistic}
              threshold={convention.threshold}
              scale={convention.scale}
            />
            <span className="score-against">
              {' '}
              · signal score {contribution.score.toFixed(2)} of its own scale · declaration weight{' '}
              {contribution.weight.toFixed(2)} (unfitted, and not the share above)
            </span>
          </div>
        )}
      </div>
    </div>
  );
}
