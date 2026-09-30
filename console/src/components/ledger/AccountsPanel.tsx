import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAccounts } from '../../api/queries';
import type { Account } from '../../api/types';
import { instant } from '../../format';
import { EmptyState } from '../EmptyState';
import { Icon } from '../icons/Icon';
import { Failure } from '../States';
import { DataTable, type Column } from './DataTable';
import { BalanceCell } from './BalanceCell';
import { EvidenceLink } from './EvidenceLink';

const PAGE_SIZE = 10;

/**
 * `GET /accounts`, offset-paginated. No create form embedded here any more —
 * that is its own page at `/ledger/accounts/new`, so this page is purely a
 * list. "Detection profile →" is the one deliberate cross-link between the
 * ledger's idea of an account and the older detection console's per-account
 * page (`/accounts/:id`) — same account id, a different concern, previously
 * not linked from here at all.
 */
export function AccountsPanel() {
  const [page, setPage] = useState(0);
  const query = useAccounts(page, PAGE_SIZE);

  const columns: Column<Account>[] = [
    {
      key: 'id',
      header: 'Account',
      render: (account) => <EvidenceLink id={account.id} />,
    },
    { key: 'name', header: 'Name', render: (account) => account.name },
    { key: 'currency', header: 'Currency', render: (account) => <span className="num">{account.currency}</span> },
    { key: 'balance', header: 'Balance', render: (account) => <BalanceCell accountId={account.id} /> },
    { key: 'createdAt', header: 'Created', render: (account) => <span className="th-note">{instant(account.createdAt)}</span> },
    {
      key: 'actions',
      header: '',
      render: (account) => (
        <div style={{ display: 'flex', gap: 8 }}>
          <Link className="btn" to={`/ledger/transactions?accountId=${account.id}`}>
            Transactions
          </Link>
          <Link className="btn" to={`/accounts/${account.id}`}>
            Detection profile <Icon name="arrow-right" />
          </Link>
        </div>
      ),
    },
  ];

  if (query.isError) {
    return <Failure what="accounts" error={query.error} onRetry={() => void query.refetch()} />;
  }

  return (
    <DataTable
      columns={columns}
      rows={query.data?.content ?? []}
      rowKey={(account) => account.id}
      page={query.data?.page}
      totalPages={query.data?.totalPages}
      totalElements={query.data?.totalElements}
      onPageChange={setPage}
      loading={query.isPending}
      emptyMessage={
        <EmptyState
          icon="accounts"
          title="No accounts yet"
          action={
            <Link className="btn btn-primary" to="/ledger/accounts/new">
              <Icon name="plus" /> Create account
            </Link>
          }
        >
          Every payment needs two real accounts to move money between.
        </EmptyState>
      }
    />
  );
}
