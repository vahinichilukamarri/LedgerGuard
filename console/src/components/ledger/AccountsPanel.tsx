import { useState } from 'react';
import { useAccounts } from '../../api/queries';
import type { Account } from '../../api/types';
import { instant } from '../../format';
import { Failure, Pending } from '../States';
import { CreateAccountForm } from './CreateAccountForm';
import { DataTable, type Column } from './DataTable';
import { BalanceCell } from './BalanceCell';
import { EvidenceLink } from './EvidenceLink';

const PAGE_SIZE = 10;

export function AccountsPanel({
  onSelectAccount,
}: {
  onSelectAccount?: (accountId: string) => void;
}) {
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
      render: (account) =>
        onSelectAccount ? (
          <button type="button" className="btn" onClick={() => onSelectAccount(account.id)}>
            Filter transactions
          </button>
        ) : null,
    },
  ];

  return (
    <section className="card">
      <h2>Accounts</h2>
      <p className="card-note">
        <code>GET /accounts</code>, offset-paginated 10 at a time, newest first. Balance is fetched and
        recomputed per row — see the <code>Derived</code> tag.
      </p>

      <CreateAccountForm />

      <div style={{ height: 20 }} />

      {query.isPending && <Pending what="accounts" />}
      {query.isError && <Failure what="accounts" error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <DataTable
          columns={columns}
          rows={query.data.content}
          rowKey={(account) => account.id}
          page={query.data.page}
          totalPages={query.data.totalPages}
          totalElements={query.data.totalElements}
          onPageChange={setPage}
          emptyMessage="No accounts yet. Create one above."
        />
      )}
    </section>
  );
}
