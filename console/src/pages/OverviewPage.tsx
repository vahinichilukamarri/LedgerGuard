import { Link } from 'react-router-dom';
import { useAccounts, useAnomalies, useIncidents, useModel, useReviewCensus, useTransactions } from '../api/queries';
import { instant } from '../format';
import { EvidenceLink } from '../components/ledger/EvidenceLink';

/**
 * No dashboard/summary endpoint exists on the backend — this page is a
 * client-side composition of six independent, already-existing reads, run in
 * parallel. Each number is accurate as of its own request; they are not a
 * single consistent snapshot, since nothing takes one. Fine for an ops
 * landing page, worth knowing if two of these numbers look momentarily out
 * of step with each other.
 */
export function OverviewPage() {
  const accounts = useAccounts(0, 5);
  const transactions = useTransactions(0, 5);
  const openIncidents = useIncidents({ status: 'OPEN' });
  const model = useModel();
  const anomalies = useAnomalies(0.5, true);
  const census = useReviewCensus();

  return (
    <div>
      <h1>Overview</h1>
      <p className="card-note" style={{ marginBottom: 20 }}>
        A landing composed from existing reads — not a backend aggregate. Each tile is its own request.
      </p>

      <div className="grid-two" style={{ marginBottom: 20 }}>
        <div className="stat-block">
          <span className="section-eyebrow">Open reconciliation incidents</span>
          <div className="score-value" style={{ fontSize: 28 }}>
            {openIncidents.data ? openIncidents.data.length : '—'}
          </div>
          <Link to="/reconciliation">Review →</Link>
        </div>
        <div className="stat-block">
          <span className="section-eyebrow">Accounts flagged (statistical or model)</span>
          <div className="score-value" style={{ fontSize: 28 }}>
            {anomalies.data ? anomalies.data.length : '—'}
          </div>
          <Link to="/anomalies">Review →</Link>
        </div>
        <div className="stat-block">
          <span className="section-eyebrow">Model status</span>
          <div className="score-value" style={{ fontSize: 20 }}>
            {model.isPending ? '—' : model.data ? `Trained ${instant(model.data.trainedAt)}` : 'No model trained'}
          </div>
          <Link to="/model">Detail →</Link>
        </div>
        <div className="stat-block">
          <span className="section-eyebrow">Review pool</span>
          <div className="score-value" style={{ fontSize: 20 }}>
            {census.data ? `${census.data.flagged} flagged / ${census.data.unflagged} unflagged` : '—'}
          </div>
          <Link to="/validation">Review →</Link>
        </div>
      </div>

      <section className="card">
        <h2>Recent accounts</h2>
        {accounts.data && accounts.data.content.length > 0 ? (
          <ul>
            {accounts.data.content.map((account) => (
              <li key={account.id}>
                <strong>{account.name}</strong> — <EvidenceLink id={account.id} /> ({account.currency})
              </li>
            ))}
          </ul>
        ) : (
          <p className="th-note">No accounts yet.</p>
        )}
        <Link to="/ledger">Go to ledger →</Link>
      </section>

      <section className="card">
        <h2>Recent transactions</h2>
        {transactions.data && transactions.data.content.length > 0 ? (
          <ul>
            {transactions.data.content.map((transaction) => (
              <li key={transaction.id}>
                <strong>{transaction.description}</strong> — <EvidenceLink id={transaction.id} /> (
                {transaction.currency})
              </li>
            ))}
          </ul>
        ) : (
          <p className="th-note">No transactions yet.</p>
        )}
        <Link to="/ledger">Go to ledger →</Link>
      </section>
    </div>
  );
}
