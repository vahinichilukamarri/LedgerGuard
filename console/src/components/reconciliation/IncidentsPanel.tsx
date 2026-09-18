import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useIncidents, useResolveIncident } from '../../api/queries';
import type { DiscrepancyType, IncidentStatus, ReconciliationIncident, Severity } from '../../api/types';
import { instant } from '../../format';
import { describe, Failure, Pending } from '../States';
import { ConfirmDialog } from '../ledger/ConfirmDialog';
import { DataTable, type Column } from '../ledger/DataTable';
import { EvidenceLink } from '../ledger/EvidenceLink';
import { SeverityBadge } from '../ledger/SeverityBadge';
import { StatusPill } from '../ledger/StatusPill';

const TYPES: DiscrepancyType[] = [
  'MISSING_SETTLEMENT',
  'AMOUNT_MISMATCH',
  'DUPLICATE_SETTLEMENT',
  'STATUS_MISMATCH',
  'UNEXPECTED_EXTERNAL_TRANSACTION',
];
const SEVERITIES: Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const STATUSES: IncidentStatus[] = ['OPEN', 'RESOLVED'];

/**
 * `GET /reconciliation/incidents` has no pagination — every filtered match
 * comes back in one response. Filtering is still server-side (the four query
 * params), just not paged; the table below renders whatever the filter
 * narrows it to.
 */
export function IncidentsPanel() {
  const [type, setType] = useState('');
  const [severity, setSeverity] = useState('');
  const [status, setStatus] = useState('OPEN');
  const [resolving, setResolving] = useState<ReconciliationIncident | null>(null);

  const query = useIncidents({
    type: type || undefined,
    severity: severity || undefined,
    status: status || undefined,
  });
  const resolveMutation = useResolveIncident();

  const columns: Column<ReconciliationIncident>[] = [
    { key: 'severity', header: 'Severity', render: (incident) => <SeverityBadge severity={incident.severity} /> },
    { key: 'type', header: 'Type', render: (incident) => <StatusPill label={incident.type} tone="info" /> },
    { key: 'status', header: 'Status', render: (incident) => <StatusPill label={incident.status} tone={incident.status === 'OPEN' ? 'caution' : 'positive'} /> },
    {
      key: 'transaction',
      header: 'Transaction',
      render: (incident) => (incident.transactionId ? <EvidenceLink id={incident.transactionId} /> : <span className="th-note">none</span>),
    },
    {
      key: 'difference',
      header: 'Difference (minor units)',
      render: (incident) => (incident.differenceMinor === null ? <span className="th-note">—</span> : <span className="num">{incident.differenceMinor}</span>),
    },
    { key: 'createdAt', header: 'Filed', render: (incident) => <span className="th-note">{instant(incident.createdAt)}</span> },
    {
      key: 'actions',
      header: '',
      render: (incident) => (
        <div style={{ display: 'flex', gap: 8 }}>
          <Link className="btn" to={`/reconciliation/incidents/${incident.id}`} state={{ incident }}>
            Evidence
          </Link>
          {incident.status === 'OPEN' && (
            <button type="button" className="btn" onClick={() => setResolving(incident)}>
              Resolve…
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <section className="card">
      <h2>Incidents</h2>
      <p className="card-note">
        <code>GET /reconciliation/incidents</code>, filtered by type, severity and status. Severity here is
        the one place this console shows a real, backend-computed severity scale — nowhere else.
      </p>

      <div className="toolbar">
        <label className="field">
          <span className="field-label">Type</span>
          <select value={type} onChange={(event) => setType(event.target.value)}>
            <option value="">Any</option>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span className="field-label">Severity</span>
          <select value={severity} onChange={(event) => setSeverity(event.target.value)}>
            <option value="">Any</option>
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span className="field-label">Status</span>
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">Any</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div style={{ height: 18 }} />

      {query.isPending && <Pending what="incidents" />}
      {query.isError && <Failure what="incidents" error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <DataTable
          columns={columns}
          rows={query.data}
          rowKey={(incident) => incident.id}
          emptyMessage="No incidents match this filter."
        />
      )}

      <ConfirmDialog
        open={resolving !== null}
        title="Resolve incident"
        description={
          resolving && (
            <>
              Mark this {resolving.severity.toLowerCase()} {resolving.type} incident resolved? The row is kept
              — nothing is deleted — but it drops out of the OPEN filter.
            </>
          )
        }
        confirmLabel="Resolve"
        busy={resolveMutation.isPending}
        onConfirm={() => {
          if (resolving) {
            resolveMutation.mutate(resolving.id, { onSuccess: () => setResolving(null) });
          }
        }}
        onCancel={() => setResolving(null)}
      />
      {resolveMutation.isError && <p className="form-error">{describe(resolveMutation.error)}</p>}
    </section>
  );
}
