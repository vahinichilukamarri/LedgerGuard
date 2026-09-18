import { useAccountBalance } from '../../api/queries';
import { MoneyAmount } from './MoneyAmount';
import { DerivedTag } from './DerivedTag';

/**
 * One request per row, bounded to the rendered page — the same trade-off
 * `usePageLabels` already makes for review status on the detection ranking.
 * There is no bulk balance endpoint, and a balance is derived from postings
 * on every read, so there is nothing to bulk-fetch from underneath it either.
 */
export function BalanceCell({ accountId }: { accountId: string }) {
  const query = useAccountBalance(accountId);

  if (query.isPending) {
    return <span className="th-note">loading…</span>;
  }
  if (query.isError) {
    return <span className="th-note">balance unavailable</span>;
  }

  return (
    <span className="balance-cell">
      <MoneyAmount amount={query.data.balance} currency={query.data.currency} />
      <DerivedTag />
    </span>
  );
}
