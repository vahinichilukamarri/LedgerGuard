import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAnomalies, usePageLabels } from '../api/queries';
import { Failure, Pending } from '../components/States';
import { FilterBar } from '../components/anomalies/FilterBar';
import { AnomalyTable } from '../components/anomalies/AnomalyTable';
import {
  PAGE_SIZE,
  filterRows,
  pageCount,
  pageOf,
  sortRows,
  type SortDirection,
  type SortKey,
} from '../components/anomalies/ranking';
import type { DisplayState } from '../api/agreement';

/**
 * Accounts either layer considers elevated.
 *
 * <h2>One request, no model calls</h2>
 *
 * The whole table comes from a single `GET /detection/anomalies`. Filtering,
 * sorting and paging are applied to that array in the browser, which the page
 * states in the open — the endpoint takes no sort or page parameters, and a
 * client-side page presented as a server-side one would misrepresent what the
 * reviewer is looking at.
 *
 * <h2>Two things this page will not do</h2>
 *
 * It will not rank by a blended score, because there is no blended score and
 * inventing one here would reintroduce at the presentation layer the conflation
 * the backend spent Phase 9 avoiding. And it will not reduce the agreement state
 * to a single flag column, for the same reason.
 */
export function AnomaliesPage() {
  const [params, setParams] = useSearchParams();

  const minScore = numberParam(params.get('minScore'), 0.5);
  const includeMlOnly = params.get('includeMlOnly') !== 'false';
  const search = params.get('q') ?? '';
  const states = (params.getAll('state') as DisplayState[]) ?? [];
  const sort = (params.get('sort') as SortKey | null) ?? 'rank';
  const direction = (params.get('dir') as SortDirection | null) ?? 'desc';
  const page = Math.max(0, numberParam(params.get('page'), 0));

  const query = useAnomalies(minScore, includeMlOnly);
  const rows = useMemo(() => query.data ?? [], [query.data]);

  const visible = useMemo(() => {
    const filtered = filterRows(rows, search, states);
    return sortRows(filtered, sort, direction);
    // `states` is a fresh array each render; its contents are what matter.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, search, states.join(','), sort, direction]);

  const pages = pageCount(visible.length);
  const safePage = Math.min(page, pages - 1);
  const pageRows = useMemo(() => pageOf(visible, safePage), [visible, safePage]);

  // Labels for the rendered page only. See `usePageLabels`: there is no bulk
  // endpoint, so this is one request per visible row and is bounded here rather
  // than at the length of the ranking.
  const labels = usePageLabels(pageRows.map((row) => row.accountId));

  function update(patch: Record<string, string | string[] | undefined>, resetPage = true) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(patch)) {
      next.delete(key);
      if (Array.isArray(value)) {
        value.forEach((item) => next.append(key, item));
      } else if (value !== undefined && value !== '') {
        next.set(key, value);
      }
    }
    if (resetPage) {
      next.delete('page');
    }
    setParams(next, { replace: true });
  }

  return (
    <>
      <section className="card">
        <h2>Accounts either layer considers elevated</h2>
        <p className="card-note">
          An account appears if <em>either</em> layer flags it. The order the API returns is the
          statistical composite descending, with the isolation score as a tie-break — not a blended
          score, because there is no blended score. Nothing on this page calls a language model:
          narratives are generated on the detail view alone.
        </p>
        <FilterBar
          minScore={minScore}
          includeMlOnly={includeMlOnly}
          search={search}
          states={states}
          onChange={(patch) =>
            update({
              minScore: patch.minScore !== undefined ? String(patch.minScore) : undefined,
              includeMlOnly:
                patch.includeMlOnly !== undefined ? String(patch.includeMlOnly) : undefined,
              q: patch.search,
              state: patch.states,
            })
          }
        />
      </section>

      {query.isPending && <Pending what="the ranking" />}

      {query.isError && (
        <Failure what="the ranking" error={query.error} onRetry={() => void query.refetch()} />
      )}

      {query.isSuccess && visible.length === 0 && (
        <p className="empty">
          No account in the ranking matches these filters. That is a statement about the filters and
          the {rows.length} rows the API returned, not about the ledger.
        </p>
      )}

      {query.isSuccess && visible.length > 0 && (
        <section className="card">
          <p className="card-note">
            Showing {pageRows.length} of {visible.length} matching rows
            {visible.length !== rows.length ? ` (${rows.length} returned by the API)` : ''}. Paging,
            sorting and the text filter are applied in the browser — the endpoint offers no
            parameters for them.
          </p>

          <AnomalyTable
            rows={pageRows}
            firstRankOnPage={safePage * PAGE_SIZE + 1}
            labels={labels}
            sort={sort}
            direction={direction}
            onSort={(key) =>
              update(
                {
                  sort: key,
                  dir: sort === key && direction === 'desc' ? 'asc' : 'desc',
                },
                true,
              )
            }
          />

          <div className="pager">
            <button
              type="button"
              disabled={safePage === 0}
              onClick={() => update({ page: String(safePage - 1) }, false)}
            >
              Previous
            </button>
            <span>
              Page {safePage + 1} of {pages}
            </span>
            <button
              type="button"
              disabled={safePage >= pages - 1}
              onClick={() => update({ page: String(safePage + 1) }, false)}
            >
              Next
            </button>
          </div>

          <p className="read-only-note">
            This console is read-only. Labelling happens through the validation API, deliberately —
            see CONSOLE_REPORT.md for why a page that displays scores is the wrong place to record a
            verdict about them.
          </p>
        </section>
      )}
    </>
  );
}

function numberParam(raw: string | null, fallback: number): number {
  if (raw === null) {
    return fallback;
  }
  const parsed = Number(raw);
  return Number.isNaN(parsed) ? fallback : parsed;
}
