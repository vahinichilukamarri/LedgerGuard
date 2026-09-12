import { useQuery, useQueries, type UseQueryResult } from '@tanstack/react-query';
import { fetchAnomalies, fetchExplanation, fetchLabels, fetchModel } from './client';
import type { AccountAssessment, AccountExplanation, AccountLabel, ModelInfo } from './types';

/**
 * Server state, and only server state.
 *
 * There is no client-side cache layer on top of this. Phase 11 already caches
 * narratives on the server, and a second cache here would be a second thing that
 * can be stale, with the console's copy winning — so the query cache holds
 * responses briefly and nothing derives a stored value from them.
 */

const keys = {
  anomalies: (minScore: number, includeMlOnly: boolean) =>
    ['anomalies', minScore, includeMlOnly] as const,
  explanation: (accountId: string, preferTemplate: boolean) =>
    ['explanation', accountId, preferTemplate] as const,
  labels: (accountId: string) => ['labels', accountId] as const,
  model: () => ['model'] as const,
};

export function useAnomalies(minScore: number, includeMlOnly: boolean) {
  return useQuery({
    queryKey: keys.anomalies(minScore, includeMlOnly),
    queryFn: () => fetchAnomalies(minScore, includeMlOnly),
  });
}

/**
 * The deterministic explanation. Fast, and the one the page renders against.
 *
 * Every number on the detail view comes from this query, never from the default
 * one — so the arithmetic is on screen whether or not a model call succeeds, and
 * a slow narrative delays prose rather than evidence.
 */
export function useTemplateExplanation(accountId: string) {
  return useQuery({
    queryKey: keys.explanation(accountId, true),
    queryFn: () => fetchExplanation(accountId, true),
  });
}

/**
 * The default narrative, which may be written by a hosted model.
 *
 * A separate query on purpose. It is the slowest call in the system, and pairing
 * it with the numbers would hold four kilobytes of arithmetic behind a model
 * round trip. Its failure is contained: the template above is already rendered.
 */
export function useDefaultNarrative(accountId: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.explanation(accountId, false),
    queryFn: () => fetchExplanation(accountId, false),
    enabled,
    // The narrative is not evidence, and a model that just timed out will
    // probably time out again. One attempt, then say so.
    retry: false,
  });
}

export function useLabels(accountId: string) {
  return useQuery({
    queryKey: keys.labels(accountId),
    queryFn: () => fetchLabels(accountId),
  });
}

/**
 * Labels for the accounts on the current page, and no others.
 *
 * There is no bulk label endpoint — `GET /validation/labels/{accountId}` is
 * per-account — so a review-status column costs one request per visible row.
 * Bounding it to the rendered page keeps that at a page size rather than at the
 * length of the ranking, and the column header says where the data comes from
 * rather than letting it look like part of the ranking response.
 */
export function usePageLabels(accountIds: string[]): Map<string, UseQueryResult<AccountLabel[]>> {
  const results = useQueries({
    queries: accountIds.map((accountId) => ({
      queryKey: keys.labels(accountId),
      queryFn: () => fetchLabels(accountId),
    })),
  });

  const byAccount = new Map<string, UseQueryResult<AccountLabel[]>>();
  accountIds.forEach((accountId, index) => {
    const result = results[index];
    if (result) {
      byAccount.set(accountId, result as UseQueryResult<AccountLabel[]>);
    }
  });
  return byAccount;
}

/** null is a valid answer: no model has been trained. */
export function useModel() {
  return useQuery<ModelInfo | null>({
    queryKey: keys.model(),
    queryFn: fetchModel,
  });
}

export type { AccountAssessment, AccountExplanation, AccountLabel, ModelInfo };
