import { useReviewCensus } from '../../api/queries';
import { Failure, Pending } from '../States';

/** `total` is computed here, not trusted from the wire — it is a derived method on the Java record, not a component of it. */
export function CensusPanel() {
  const query = useReviewCensus();

  if (query.isPending) {
    return <Pending what="the review census" />;
  }
  if (query.isError) {
    return <Failure what="the review census" error={query.error} onRetry={() => void query.refetch()} />;
  }

  const total = query.data.flagged + query.data.unflagged;

  return (
    <dl className="facts">
      <dt>Flagged pool</dt>
      <dd>{query.data.flagged}</dd>
      <dt>Unflagged pool</dt>
      <dd>{query.data.unflagged}</dd>
      <dt>Total accounts</dt>
      <dd>{total}</dd>
    </dl>
  );
}
