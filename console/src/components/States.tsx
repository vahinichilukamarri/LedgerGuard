import { ApiError } from '../api/client';

/**
 * Loading and failure, stated explicitly at every fetch.
 *
 * Not a global spinner. A page that greys out entirely while one of its four
 * queries is in flight tells a reviewer nothing about which part is missing, and
 * on this console the parts have very different standing: the arithmetic is
 * fast and final, the narrative is slow and optional, and the labels are neither.
 */
export function Pending({ what }: { what: string }) {
  return (
    <p className="pending" role="status" aria-live="polite">
      Loading {what}…
    </p>
  );
}

/**
 * A failed fetch, described as a failed fetch.
 *
 * Deliberately never phrased as a fact about the account. "Could not load the
 * explanation" and "this account has no explanation" are different statements,
 * and only the first one is true here.
 */
export function Failure({
  what,
  error,
  onRetry,
}: {
  what: string;
  error: unknown;
  onRetry?: () => void;
}) {
  return (
    <div className="failure" role="alert">
      <h2>Could not load {what}</h2>
      <p>
        {describe(error)} This is a problem reaching the API, not a finding about the data behind
        it.
      </p>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function describe(error: unknown): string {
  if (error instanceof ApiError) {
    return error.status === 0
      ? `${error.message}`
      : `The API answered ${error.status} for ${error.url}.`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'The request failed for an unrecognised reason.';
}
