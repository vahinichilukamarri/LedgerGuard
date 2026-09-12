import type { AccountAssessment } from '../../api/types';
import { displayAgreement, type DisplayState } from '../../api/agreement';

/**
 * Client-side filtering, sorting and paging over the ranking.
 *
 * <h2>Why it is client-side</h2>
 *
 * `GET /detection/anomalies` takes two parameters — `minScore` and
 * `includeMlOnly` — and returns a plain array. There is no page, no sort and no
 * offset, so the console does those itself over the array it already has. That
 * is a real limit rather than a design choice, and the table says so in the
 * open rather than presenting a client-side page as if the server had produced
 * it.
 *
 * Pure functions, separated from the component, because ordering rules are the
 * part of a table most worth testing and least worth mounting a DOM to test.
 */

export type SortKey = 'rank' | 'statistical' | 'ml' | 'evidence';
export type SortDirection = 'asc' | 'desc';

export const PAGE_SIZE = 25;

export interface RankingQuery {
  search: string;
  states: DisplayState[];
  sort: SortKey;
  direction: SortDirection;
  page: number;
}

/** The state each row displays, computed once so filtering and rendering agree. */
export function stateOf(row: AccountAssessment): DisplayState {
  return displayAgreement({
    agreement: row.explanation.agreement,
    corroborated: row.explanation.corroborated,
    modelDriversOutsideView: row.explanation.modelDriversOutsideStatisticalView,
  }).state;
}

export function filterRows(rows: AccountAssessment[], search: string, states: DisplayState[]): AccountAssessment[] {
  const needle = search.trim().toLowerCase();
  return rows.filter((row) => {
    if (needle && !row.accountId.toLowerCase().includes(needle)) {
      return false;
    }
    if (states.length > 0 && !states.includes(stateOf(row))) {
      return false;
    }
    return true;
  });
}

/**
 * Sorting, with the server's own order as the default and as the tie-break.
 *
 * `rank` is not a sort at all — it is the order the endpoint returned, which is
 * composite descending with the isolation score as a tie-break and the account
 * id after that. Keeping it as an explicit, selectable option means the console
 * never silently reorders the ranking while looking like it is showing it.
 */
export function sortRows(
  rows: AccountAssessment[],
  sort: SortKey,
  direction: SortDirection,
): AccountAssessment[] {
  if (sort === 'rank') {
    return direction === 'desc' ? [...rows] : [...rows].reverse();
  }

  const sign = direction === 'desc' ? -1 : 1;
  return [...rows].sort((left, right) => {
    const delta = valueOf(left, sort) - valueOf(right, sort);
    // Ties fall back to the server's ranking position, so two runs agree exactly
    // and no row jumps between renders.
    return delta === 0 ? rows.indexOf(left) - rows.indexOf(right) : sign * delta;
  });
}

function valueOf(row: AccountAssessment, sort: Exclude<SortKey, 'rank'>): number {
  switch (sort) {
    case 'statistical':
      return row.statisticalScore;
    case 'ml':
      // An account with no model score sorts below every account that has one,
      // rather than alongside the accounts the model scored zero.
      return row.ml.available && row.ml.score !== null ? row.ml.score : Number.NEGATIVE_INFINITY;
    case 'evidence':
      return row.applicableSignals;
  }
}

export function pageOf<T>(rows: T[], page: number, size = PAGE_SIZE): T[] {
  return rows.slice(page * size, page * size + size);
}

export function pageCount(total: number, size = PAGE_SIZE): number {
  return Math.max(1, Math.ceil(total / size));
}
