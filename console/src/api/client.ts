import type { AccountAssessment, AccountExplanation, AccountLabel, ModelInfo } from './types';

/**
 * The HTTP layer, and nothing else.
 *
 * Relative paths throughout: in development Vite proxies them to the Spring
 * application, and in any real deployment the console would be served from the
 * same origin. No base URL to configure, and no CORS asked of the backend.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly url: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function get<T>(url: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, { headers: { Accept: 'application/json' } });
  } catch (cause) {
    // A network failure and an HTTP error are different things for a reviewer:
    // one means the backend is not reachable, the other means it answered.
    throw new ApiError(0, url, `Could not reach the API at ${url}. Is the backend running?`);
  }

  if (!response.ok) {
    throw new ApiError(response.status, url, `${response.status} ${response.statusText} from ${url}`);
  }
  return (await response.json()) as T;
}

/**
 * The ranked list.
 *
 * One request for the whole ranking, and **no LLM call anywhere in it**: this
 * endpoint serves the template digest by construction (`DetectionController`
 * generates a model narrative only on the detail endpoint), which is the Phase
 * 11 cost decision the console is built around. Sorting, filtering and paging
 * happen client-side over this array because the endpoint offers no parameters
 * for them.
 */
export function fetchAnomalies(minScore: number, includeMlOnly: boolean): Promise<AccountAssessment[]> {
  const query = new URLSearchParams({
    minScore: String(minScore),
    includeMlOnly: String(includeMlOnly),
  });
  return get<AccountAssessment[]>(`/detection/anomalies?${query}`);
}

/**
 * The full explanation for one account.
 *
 * `preferTemplate` maps to `?narrative=template`, which skips the model. The
 * default path may call a hosted model and is the slowest request in the system;
 * the console fetches the two as separate queries so the template is on screen
 * while the model's prose is still in flight.
 */
export function fetchExplanation(accountId: string, preferTemplate: boolean): Promise<AccountExplanation> {
  const suffix = preferTemplate ? '?narrative=template' : '';
  return get<AccountExplanation>(`/detection/accounts/${accountId}/explanation${suffix}`);
}

/** Every label on an account, oldest and newest alike — a revised verdict does not delete the first. */
export function fetchLabels(accountId: string): Promise<AccountLabel[]> {
  return get<AccountLabel[]>(`/validation/labels/${accountId}`);
}

/** The loaded model, or null. A 404 here means "none trained", which is not an error. */
export async function fetchModel(): Promise<ModelInfo | null> {
  try {
    return await get<ModelInfo>('/detection/model');
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}
