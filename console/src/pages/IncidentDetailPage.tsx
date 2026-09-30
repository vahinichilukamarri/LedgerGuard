import { Link, useLocation, useParams } from 'react-router-dom';
import { useIncidents, useSettlementRecords, useTransaction } from '../api/queries';
import type { ReconciliationIncident } from '../api/types';
import { instant, minorToMajor } from '../format';
import { PageHeader } from '../components/PageHeader';
import { Failure, Pending } from '../components/States';
import { EvidenceLink } from '../components/ledger/EvidenceLink';
import { MoneyAmount } from '../components/ledger/MoneyAmount';
import { SeverityBadge } from '../components/ledger/SeverityBadge';
import { StatusPill } from '../components/ledger/StatusPill';

/**
 * There is no `GET /reconciliation/incidents/{id}` — this page is normally
 * handed the row it should render via router state from `IncidentsPanel`'s
 * link. A direct visit (a bookmark, a page refresh) has no state to read, so
 * it falls back to the unbounded, unfiltered incidents list and finds the
 * row client-side. That fallback is only as fast as the incident table
 * itself, which is the backend limitation this page inherits rather than
 * hides.
 */
export function IncidentDetailPage() {
  const { incidentId } = useParams<{ incidentId: string }>();
  const location = useLocation();
  const stateIncident = (location.state as { incident?: ReconciliationIncident } | null)?.incident;

  const fallback = useIncidents({});
  const incident = stateIncident ?? fallback.data?.find((row) => row.id === incidentId);

  if (!stateIncident && fallback.isPending) {
    return <Pending what="the incident" />;
  }
  if (!stateIncident && fallback.isError) {
    return <Failure what="the incident" error={fallback.error} onRetry={() => void fallback.refetch()} />;
  }
  if (!incident) {
    return (
      <section className="card">
        <h2>Incident not found</h2>
        <p className="card-note">
          No open or resolved incident with id <code>{incidentId}</code> was found.
        </p>
        <Link className="breadcrumb" to="/reconciliation">
          ← Back to reconciliation
        </Link>
      </section>
    );
  }

  return (
    <div>
      <Link className="breadcrumb" to="/reconciliation">
        ← Back to reconciliation
      </Link>
      <PageHeader
        icon="reconciliation"
        title="Incident evidence"
        description="The ledger's side and the processor's side of one discrepancy, shown next to each other."
      />

      <section className="card">
        <div className="account-title-row">
          <h2>{incident.type}</h2>
          <SeverityBadge severity={incident.severity} />
        </div>
        <p className="card-note">{incident.detail}</p>
        <dl className="facts">
          <dt>Status</dt>
          <dd>
            <StatusPill label={incident.status} tone={incident.status === 'OPEN' ? 'caution' : 'positive'} />
          </dd>
          <dt>Run</dt>
          <dd>
            <EvidenceLink id={incident.runId} />
          </dd>
          <dt>Filed</dt>
          <dd>{instant(incident.createdAt)}</dd>
          {incident.resolvedAt && (
            <>
              <dt>Resolved</dt>
              <dd>{instant(incident.resolvedAt)}</dd>
            </>
          )}
          {incident.currency && (
            <>
              <dt>Internal amount</dt>
              <dd>
                {incident.internalAmountMinor === null ? (
                  '—'
                ) : (
                  <MoneyAmount
                    amount={minorToMajor(incident.internalAmountMinor, incident.currency)}
                    currency={incident.currency}
                  />
                )}
              </dd>
              <dt>External amount</dt>
              <dd>
                {incident.externalAmountMinor === null ? (
                  '—'
                ) : (
                  <MoneyAmount
                    amount={minorToMajor(incident.externalAmountMinor, incident.currency)}
                    currency={incident.currency}
                  />
                )}
              </dd>
              <dt>Difference</dt>
              <dd>
                {incident.differenceMinor === null ? (
                  '—'
                ) : (
                  <MoneyAmount
                    amount={minorToMajor(incident.differenceMinor, incident.currency)}
                    currency={incident.currency}
                  />
                )}
              </dd>
            </>
          )}
          {incident.internalStatus && (
            <>
              <dt>Internal status</dt>
              <dd>{incident.internalStatus}</dd>
            </>
          )}
          {incident.externalStatus && (
            <>
              <dt>External status</dt>
              <dd>{incident.externalStatus}</dd>
            </>
          )}
        </dl>
      </section>

      {incident.transactionId && <InternalEvidence transactionId={incident.transactionId} />}
      {incident.transactionId && <ExternalEvidence transactionId={incident.transactionId} />}
    </div>
  );
}

function InternalEvidence({ transactionId }: { transactionId: string }) {
  const query = useTransaction(transactionId);

  return (
    <section className="card">
      <h2>Internal side — the ledger's transaction</h2>
      {query.isPending && <Pending what="the transaction" />}
      {query.isError && <Failure what="the transaction" error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <table className="data">
          <thead>
            <tr>
              <th>Account</th>
              <th>Type</th>
              <th>Amount</th>
            </tr>
          </thead>
          <tbody>
            {query.data.postings.map((posting) => (
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
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function ExternalEvidence({ transactionId }: { transactionId: string }) {
  const query = useSettlementRecords(transactionId);

  return (
    <section className="card">
      <h2>External side — what the simulated processor recorded</h2>
      {query.isPending && <Pending what="settlement records" />}
      {query.isError && <Failure what="settlement records" error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && query.data.length === 0 && (
        <p className="card-note">No settlement record references this transaction — which is itself the finding for a MISSING_SETTLEMENT incident.</p>
      )}
      {query.data && query.data.length > 0 && (
        <table className="data">
          <thead>
            <tr>
              <th>External id</th>
              <th>Status</th>
              <th>Amount</th>
              <th>Settled</th>
            </tr>
          </thead>
          <tbody>
            {query.data.map((record) => (
              <tr key={record.id}>
                <td className="th-note">{record.externalId}</td>
                <td>
                  <StatusPill label={record.status} tone={record.status === 'SETTLED' ? 'positive' : 'caution'} />
                </td>
                <td>
                  <MoneyAmount amount={minorToMajor(record.amountMinor, record.currency)} currency={record.currency} />
                </td>
                <td className="th-note">{instant(record.settledAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
