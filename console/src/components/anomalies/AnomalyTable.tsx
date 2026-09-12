import { Link } from 'react-router-dom';
import type { UseQueryResult } from '@tanstack/react-query';
import type { AccountAssessment, AccountLabel } from '../../api/types';
import { AgreementChip } from '../uncertainty/AgreementChip';
import { ScoreReadout } from '../uncertainty/ScoreReadout';
import { LabelSummary } from '../uncertainty/LabelChip';
import { humanise, shortAccount } from '../uncertainty/format';
import type { SortDirection, SortKey } from './ranking';

/**
 * The ranking.
 *
 * <h2>No LLM call happens here</h2>
 *
 * Every column is served by the single `GET /detection/anomalies` response.
 * Phase 11 put narrative generation on the detail endpoint alone, and a table
 * that fetched an explanation per row to fill a column would spend fifty model
 * calls helping someone choose which one account to open. The `summary` shown in
 * the digest is the template, generated without a model.
 *
 * <h2>The review column is the exception, and says so</h2>
 *
 * There is no bulk label endpoint, so review status costs one request per
 * visible row. The header states that rather than letting the column look like
 * part of the ranking response, and the fetch is bounded to the rendered page.
 */
export function AnomalyTable({
  rows,
  firstRankOnPage,
  labels,
  sort,
  direction,
  onSort,
}: {
  rows: AccountAssessment[];
  firstRankOnPage: number;
  labels: Map<string, UseQueryResult<AccountLabel[]>>;
  sort: SortKey;
  direction: SortDirection;
  onSort: (key: SortKey) => void;
}) {
  return (
    <div className="table-scroll">
      <table className="ranking">
        <thead>
          <tr>
            <th scope="col">
              <SortButton label="#" sortKey="rank" active={sort} direction={direction} onSort={onSort} />
              <span className="th-note">order the API returned</span>
            </th>
            <th scope="col">Account</th>
            <th scope="col">
              <SortButton
                label="Statistical composite"
                sortKey="statistical"
                active={sort}
                direction={direction}
                onSort={onSort}
              />
              <span className="th-note">0.50 convention, unfitted</span>
            </th>
            <th scope="col">
              <SortButton
                label="Isolation score"
                sortKey="ml"
                active={sort}
                direction={direction}
                onSort={onSort}
              />
              <span className="th-note">0.60 convention, unfitted</span>
            </th>
            <th scope="col">
              How the layers relate
              <span className="th-note">never combined into one indicator</span>
            </th>
            <th scope="col">
              <SortButton
                label="Evidence"
                sortKey="evidence"
                active={sort}
                direction={direction}
                onSort={onSort}
              />
              <span className="th-note">signals able to judge</span>
            </th>
            <th scope="col">
              Named drivers
              <span className="th-note">from the template digest</span>
            </th>
            <th scope="col">
              Review status
              <span className="th-note">fetched per row; no bulk endpoint exists</span>
            </th>
          </tr>
        </thead>

        <tbody>
          {rows.map((row, index) => (
            <tr key={row.accountId}>
              <td className="rank-cell">{firstRankOnPage + index}</td>

              <td>
                <Link className="account-link" to={`/accounts/${row.accountId}`}>
                  {shortAccount(row.accountId)}
                </Link>
              </td>

              <td>
                <ScoreReadout value={row.statisticalScore} kind="statistical" />
              </td>

              <td>
                <ScoreReadout
                  value={row.ml.available ? row.ml.score : null}
                  kind="model"
                  absentReason={row.ml.unavailableReason}
                />
              </td>

              <td>
                <AgreementChip
                  compact
                  input={{
                    agreement: row.explanation.agreement,
                    corroborated: row.explanation.corroborated,
                    modelDriversOutsideView: row.explanation.modelDriversOutsideStatisticalView,
                  }}
                />
              </td>

              <td>
                <span className="num">{row.applicableSignals}</span> of 5
                <span className="th-note">
                  {row.wellEvidenced
                    ? 'well evidenced'
                    : 'thinly evidenced — most of the evidence this system can gather was never available'}
                </span>
              </td>

              <td>
                <Drivers
                  statistical={row.explanation.statisticalDrivers}
                  model={row.explanation.modelDrivers}
                  hasModel={row.ml.available}
                />
              </td>

              <td>
                <ReviewCell result={labels.get(row.accountId)} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Drivers from each layer, listed under their own layer.
 *
 * Merged into one list they would read as one set of reasons. They are two sets
 * of reasons produced by two things that may not agree, and the account of which
 * is which is the column's only real content.
 */
function Drivers({
  statistical,
  model,
  hasModel,
}: {
  statistical: string[];
  model: string[];
  hasModel: boolean;
}) {
  return (
    <>
      <span className="th-note">
        statistical: {statistical.length > 0 ? statistical.map(humanise).join(', ') : 'no signal fired'}
      </span>
      <span className="th-note">
        model:{' '}
        {!hasModel
          ? 'no model trained'
          : model.length > 0
            ? model.map(humanise).join(', ')
            : 'nothing isolated it faster than an even split would have'}
      </span>
    </>
  );
}

function ReviewCell({ result }: { result: UseQueryResult<AccountLabel[]> | undefined }) {
  if (!result || result.isPending) {
    return <span className="th-note">checking…</span>;
  }
  if (result.isError) {
    // Not "unlabelled": a failed lookup and an account nobody has judged are
    // different, and only one of them is a fact about the account.
    return <span className="th-note">label lookup failed</span>;
  }
  return <LabelSummary labels={result.data ?? []} />;
}

function SortButton({
  label,
  sortKey,
  active,
  direction,
  onSort,
}: {
  label: string;
  sortKey: SortKey;
  active: SortKey;
  direction: SortDirection;
  onSort: (key: SortKey) => void;
}) {
  const on = active === sortKey;
  return (
    <button
      type="button"
      className="sort"
      onClick={() => onSort(sortKey)}
      aria-label={`Sort by ${label}`}
      aria-sort={on ? (direction === 'desc' ? 'descending' : 'ascending') : 'none'}
    >
      {label}
      {on ? (direction === 'desc' ? ' ↓' : ' ↑') : ''}
    </button>
  );
}
