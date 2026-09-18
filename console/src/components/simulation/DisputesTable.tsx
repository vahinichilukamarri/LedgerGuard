import { useDisputes } from '../../api/queries';
import type { DisputeRecord } from '../../api/types';
import { instant, latency } from '../uncertainty/format';
import { Failure, Pending } from '../States';
import { DataTable, type Column } from '../ledger/DataTable';
import { StatusPill } from '../ledger/StatusPill';

export function DisputesTable() {
  const query = useDisputes();

  const columns: Column<DisputeRecord>[] = [
    { key: 'externalId', header: 'External id', render: (d) => <span className="th-note">{d.externalId}</span> },
    { key: 'transactionReference', header: 'Transaction', render: (d) => <span className="th-note">{d.transactionReference}</span> },
    { key: 'reason', header: 'Reason', render: (d) => <StatusPill label={d.reason} tone={d.labelBearing ? 'caution' : 'neutral'} /> },
    { key: 'labelBearing', header: 'Label-bearing', render: (d) => (d.labelBearing ? 'Yes' : 'No') },
    { key: 'raisedAt', header: 'Raised', render: (d) => <span className="th-note">{instant(d.raisedAt)}</span> },
    { key: 'latency', header: 'Latency', render: (d) => latency(d.latencyDays * 86400) },
  ];

  return (
    <div>
      {query.isPending && <Pending what="disputes" />}
      {query.isError && <Failure what="disputes" error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <DataTable columns={columns} rows={query.data} rowKey={(d) => d.externalId} emptyMessage="No disputes raised yet." />
      )}
    </div>
  );
}
