import { useState } from 'react';
import { useSettlementRecords } from '../../api/queries';
import type { SettlementRecord } from '../../api/types';
import { instant, minorToMajor } from '../../format';
import { Failure, Pending } from '../States';
import { DataTable, type Column } from '../ledger/DataTable';
import { MoneyAmount } from '../ledger/MoneyAmount';
import { StatusPill } from '../ledger/StatusPill';

export function SettlementRecordsTable() {
  const [transactionId, setTransactionId] = useState('');
  const query = useSettlementRecords(transactionId || undefined);

  const columns: Column<SettlementRecord>[] = [
    { key: 'externalId', header: 'External id', render: (r) => <span className="th-note">{r.externalId}</span> },
    { key: 'externalReference', header: 'References', render: (r) => <span className="th-note">{r.externalReference}</span> },
    { key: 'status', header: 'Status', render: (r) => <StatusPill label={r.status} tone={r.status === 'SETTLED' ? 'positive' : 'caution'} /> },
    { key: 'amount', header: 'Amount', render: (r) => <MoneyAmount amount={minorToMajor(r.amountMinor, r.currency)} currency={r.currency} /> },
    { key: 'settledAt', header: 'Settled', render: (r) => <span className="th-note">{instant(r.settledAt)}</span> },
  ];

  return (
    <div>
      <div className="toolbar">
        <label className="field">
          <span className="field-label">Filter by transaction id</span>
          <input
            type="search"
            value={transactionId}
            onChange={(event) => setTransactionId(event.target.value)}
            placeholder="transaction id, or leave blank for all"
          />
        </label>
      </div>
      <div style={{ height: 16 }} />
      {query.isPending && <Pending what="settlement records" />}
      {query.isError && <Failure what="settlement records" error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <DataTable
          columns={columns}
          rows={query.data}
          rowKey={(r) => r.id}
          emptyMessage="No settlement records yet."
        />
      )}
    </div>
  );
}
