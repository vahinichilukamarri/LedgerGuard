import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useCreateReversal, useTransaction, useTransactions } from '../../api/queries';
import type { TransactionSummary } from '../../api/types';
import { instant } from '../../format';
import { EmptyState } from '../EmptyState';
import { Icon } from '../icons/Icon';
import { describe, Failure, Pending } from '../States';
import { ConfirmDialog } from './ConfirmDialog';
import { DataTable, type Column } from './DataTable';
import { EvidenceLink } from './EvidenceLink';
import { MoneyAmount } from './MoneyAmount';
import { StatusPill } from './StatusPill';

const PAGE_SIZE = 10;

/**
 * `GET /transactions`, filterable by account. The `accountId` filter lives
 * in the URL (`?accountId=`) rather than component state, consistent with
 * the rest of the app — `AccountsPanel`'s "Transactions" link on a row sends
 * a reviewer straight to this page pre-filtered, and the URL reproduces
 * that view for anyone it's shared with.
 */
export function TransactionsPanel() {
  const [searchParams, setSearchParams] = useSearchParams();
  const accountId = searchParams.get('accountId') ?? '';
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<string | null>(null);
  const query = useTransactions(page, PAGE_SIZE, accountId || undefined);

  function onAccountIdChange(next: string) {
    setPage(0);
    if (next) {
      setSearchParams({ accountId: next });
    } else {
      setSearchParams({});
    }
  }

  const columns: Column<TransactionSummary>[] = [
    { key: 'id', header: 'Transaction', render: (t) => <EvidenceLink id={t.id} /> },
    { key: 'description', header: 'Description', render: (t) => t.description },
    { key: 'currency', header: 'Currency', render: (t) => <span className="num">{t.currency}</span> },
    { key: 'createdAt', header: 'Posted', render: (t) => <span className="th-note">{instant(t.createdAt)}</span> },
    {
      key: 'expand',
      header: '',
      render: (t) => (
        <button type="button" className="btn" onClick={() => setExpanded(expanded === t.id ? null : t.id)}>
          {expanded === t.id ? 'Hide' : 'Postings'}
        </button>
      ),
    },
  ];

  return (
    <div>
      <div className="toolbar">
        <label className="field">
          <span className="field-label">Filter by account id</span>
          <input
            type="search"
            value={accountId}
            onChange={(event) => onAccountIdChange(event.target.value)}
            placeholder="account id, or leave blank for all"
          />
        </label>
      </div>

      <div style={{ height: 18 }} />

      {query.isError ? (
        <Failure what="transactions" error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <DataTable
          columns={columns}
          rows={query.data?.content ?? []}
          rowKey={(t) => t.id}
          page={query.data?.page}
          totalPages={query.data?.totalPages}
          totalElements={query.data?.totalElements}
          onPageChange={setPage}
          loading={query.isPending}
          emptyMessage={
            <EmptyState
              icon="transactions"
              title={accountId ? 'No transactions touch this account yet' : 'No transactions yet'}
              action={
                <Link className="btn btn-primary" to="/ledger/payments">
                  <Icon name="plus" /> Create payment
                </Link>
              }
            >
              Every transaction here comes from a posted payment, a refund, or a reversal.
            </EmptyState>
          }
        />
      )}

      {expanded && <TransactionDetail transactionId={expanded} />}
    </div>
  );
}

function TransactionDetail({ transactionId }: { transactionId: string }) {
  const query = useTransaction(transactionId);
  const [confirming, setConfirming] = useState(false);
  const reversalMutation = useCreateReversal();

  if (query.isPending) {
    return <Pending what="the transaction" />;
  }
  if (query.isError) {
    return <Failure what="the transaction" error={query.error} onRetry={() => void query.refetch()} />;
  }

  const transaction = query.data;

  return (
    <div className="stat-block" style={{ marginTop: 16 }}>
      <h3>Postings — {transaction.description}</h3>
      <table className="data">
        <thead>
          <tr>
            <th>Account</th>
            <th>Type</th>
            <th>Amount</th>
            <th>Posted</th>
          </tr>
        </thead>
        <tbody>
          {transaction.postings.map((posting) => (
            <tr key={posting.id}>
              <td>
                <EvidenceLink id={posting.accountId} />
              </td>
              <td>
                <StatusPill label={posting.type} tone={posting.type === 'DEBIT' ? 'info' : 'positive'} />
              </td>
              <td>
                <MoneyAmount amount={posting.amount} currency={posting.currency} />
              </td>
              <td className="th-note">{instant(posting.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {!reversalMutation.isSuccess && (
        <div className="form-actions" style={{ marginTop: 14 }}>
          <button type="button" className="btn btn-danger" onClick={() => setConfirming(true)}>
            Reverse transaction…
          </button>
        </div>
      )}
      {reversalMutation.isError && <p className="form-error">{describe(reversalMutation.error)}</p>}
      {reversalMutation.isSuccess && (
        <p className="form-success" style={{ marginTop: 14 }}>
          Reversed. New transaction: <EvidenceLink id={reversalMutation.data.reversalTransaction.id} />
        </p>
      )}

      <ConfirmDialog
        open={confirming}
        title="Confirm reversal"
        description={
          <>
            Post a new transaction negating every posting in <code>{transactionId}</code>? A reversal is
            single-use and cannot itself be reversed.
          </>
        }
        confirmLabel="Reverse"
        busy={reversalMutation.isPending}
        onConfirm={() =>
          reversalMutation.mutate({ transactionId }, { onSuccess: () => setConfirming(false) })
        }
        onCancel={() => setConfirming(false)}
      />
    </div>
  );
}
