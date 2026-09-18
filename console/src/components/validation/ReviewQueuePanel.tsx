import { useState, type FormEvent } from 'react';
import { useRecordLabel, useReviewNext } from '../../api/queries';
import type { Stratum, Verdict } from '../../api/types';
import { score } from '../uncertainty/format';
import { describe, Empty, Failure, Pending } from '../States';
import { EvidenceLink } from '../ledger/EvidenceLink';

const VERDICTS: Verdict[] = ['ANOMALOUS', 'BENIGN', 'UNCLEAR'];

/**
 * Blind by default, matching the backend's own stated design intent (see
 * `ReviewCandidateResponse`'s javadoc and Phase 14's CONSOLE_REPORT §5): a
 * reviewer shown a score before judging produces an opinion about the
 * detector's opinion, and every label recorded from a screen that always
 * shows scores is anchored by construction. Revealing is one explicit click,
 * and the label this queue writes carries the true `scoresVisible` for
 * whichever mode was active when the verdict was recorded.
 */
export function ReviewQueuePanel() {
  const [stratum, setStratum] = useState<Stratum>('FLAGGED');
  const [blind, setBlind] = useState(true);
  const [reviewer, setReviewer] = useState('');
  const [verdict, setVerdict] = useState<Verdict>('BENIGN');
  const [notes, setNotes] = useState('');

  const query = useReviewNext(stratum, blind);
  const labelMutation = useRecordLabel();

  function onStratumChange(next: Stratum) {
    setStratum(next);
    setBlind(true);
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (!query.data) {
      return;
    }
    labelMutation.mutate(
      {
        accountId: query.data.accountId,
        verdict,
        reviewer,
        stratum: query.data.stratum,
        scoresVisible: !blind,
        notes: notes || undefined,
      },
      {
        onSuccess: () => {
          setNotes('');
          setBlind(true);
        },
      },
    );
  }

  return (
    <div>
      <div className="toolbar">
        <label className="field">
          <span className="field-label">Stratum</span>
          <select value={stratum} onChange={(event) => onStratumChange(event.target.value as Stratum)}>
            <option value="FLAGGED">Flagged — the detector surfaced it</option>
            <option value="AUDIT">Audit — random sample, the only honest recall denominator</option>
          </select>
        </label>
      </div>

      <div style={{ height: 16 }} />

      {query.isPending && <Pending what="the next account to review" />}
      {query.isError && (
        <Failure what="the next account to review" error={query.error} onRetry={() => void query.refetch()} />
      )}
      {query.data === null && <Empty>Nothing left to review in this stratum right now.</Empty>}

      {query.data && (
        <div className="stat-block">
          <dl className="facts">
            <dt>Account</dt>
            <dd>
              <EvidenceLink id={query.data.accountId} />
            </dd>
            <dt>Stratum</dt>
            <dd>{query.data.stratum}</dd>
            <dt>Payments in baseline</dt>
            <dd>{query.data.paymentsInBaseline}</dd>
          </dl>

          {blind ? (
            <button type="button" className="btn" onClick={() => setBlind(false)} style={{ marginTop: 8 }}>
              Reveal scores
            </button>
          ) : (
            <dl className="facts" style={{ marginTop: 8 }}>
              <dt>Statistical composite</dt>
              <dd>{query.data.statisticalScore === null ? 'no model' : score(query.data.statisticalScore)}</dd>
              <dt>Isolation score</dt>
              <dd>{query.data.mlScore === null ? 'no model' : score(query.data.mlScore)}</dd>
            </dl>
          )}
          <p className="th-note" style={{ marginTop: 8 }}>
            {blind
              ? 'Scores hidden — this verdict will be recorded as unanchored.'
              : 'Scores revealed — this verdict will be recorded as anchored (scoresVisible: true).'}
          </p>

          <form className="form" onSubmit={onSubmit} style={{ marginTop: 16 }}>
            <div className="form-row">
              <label className="field">
                <span className="field-label">Verdict</span>
                <select value={verdict} onChange={(event) => setVerdict(event.target.value as Verdict)}>
                  {VERDICTS.map((v) => (
                    <option key={v} value={v}>
                      {v}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span className="field-label">Reviewer</span>
                <input type="text" value={reviewer} onChange={(event) => setReviewer(event.target.value)} required />
              </label>
            </div>
            <label className="field">
              <span className="field-label">Notes (optional)</span>
              <textarea value={notes} onChange={(event) => setNotes(event.target.value)} maxLength={2000} />
            </label>
            <div className="form-actions">
              <button type="submit" className="btn btn-primary" disabled={labelMutation.isPending}>
                {labelMutation.isPending ? 'Recording…' : 'Record verdict'}
              </button>
            </div>
            {labelMutation.isError && <p className="form-error">{describe(labelMutation.error)}</p>}
          </form>
        </div>
      )}
    </div>
  );
}
