import { Link } from 'react-router-dom';
import { useAccounts, useAnomalies, useIncidents, useModel, useReconciliationRuns, useTransactions } from '../api/queries';
import type { Severity } from '../api/types';
import { instant } from '../format';
import { EmptyState } from '../components/EmptyState';
import { Icon } from '../components/icons/Icon';
import { PageHeader } from '../components/PageHeader';
import { EvidenceLink } from '../components/ledger/EvidenceLink';

const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

const AGREEMENT_LABELS: Record<string, string> = {
  BOTH_ELEVATED: 'Both elevated',
  STATISTICAL_ONLY: 'Statistical only',
  ML_ONLY: 'Model only',
  BOTH_QUIET: 'Both quiet',
  NO_MODEL: 'No model to compare',
};

const AGREEMENT_TONE: Record<string, string> = {
  BOTH_ELEVATED: 'cat-both',
  STATISTICAL_ONLY: 'cat-statistical',
  ML_ONLY: 'cat-model',
  BOTH_QUIET: 'cat-neutral',
  NO_MODEL: 'cat-neutral',
};

const RECENT_RUNS = 5;

/**
 * No dashboard/summary endpoint exists on the backend — this page is a
 * client-side composition of five independent, already-existing reads, run in
 * parallel. Each number is accurate as of its own request; they are not a
 * single consistent snapshot, since nothing takes one. Fine for an ops
 * landing page, worth knowing if two of these numbers look momentarily out
 * of step with each other.
 *
 * Every tile here is real: no card renders a number that isn't the direct
 * result of one of the reads below. There is no "trend" computed by the
 * backend anywhere — the run-history bars below are this page reading the
 * last few real runs, not a derived metric.
 */
export function OverviewPage() {
  const openIncidents = useIncidents({ status: 'OPEN' });
  const recentRuns = useReconciliationRuns(0, RECENT_RUNS);
  const anomalies = useAnomalies(0.5, true);
  const model = useModel();
  const accounts = useAccounts(0, 5);
  const transactions = useTransactions(0, 5);

  const severityCounts: Record<Severity, number> = { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 };
  openIncidents.data?.forEach((incident) => {
    severityCounts[incident.severity] += 1;
  });
  const worstSeverity = SEVERITY_ORDER.find((severity) => severityCounts[severity] > 0);
  const openIncidentsTotal = SEVERITY_ORDER.reduce((sum, severity) => sum + severityCounts[severity], 0);

  const agreementCounts: Record<string, number> = {};
  anomalies.data?.forEach((row) => {
    const key = row.explanation.agreement ?? 'NO_MODEL';
    agreementCounts[key] = (agreementCounts[key] ?? 0) + 1;
  });
  const agreementTotal = anomalies.data?.length ?? 0;

  const runs = recentRuns.data?.content ?? [];
  const run = runs[0];

  return (
    <div>
      <PageHeader
        icon="overview"
        title="Overview"
        description="Real numbers from five independent reads — not a backend aggregate, so two tiles can be a moment apart. Nothing below is a placeholder: every figure is the direct result of the endpoint named on it."
      />

      <div className="kpi-grid">
        <div className={`kpi-card${worstSeverity ? ` kpi-tone-severity-${worstSeverity.toLowerCase()}` : ''}`}>
          <span className="kpi-label">Open reconciliation incidents</span>
          <span className="kpi-value">{openIncidents.isPending ? '—' : openIncidentsTotal}</span>
          {openIncidentsTotal > 0 && (
            <div className="kpi-breakdown">
              {SEVERITY_ORDER.filter((severity) => severityCounts[severity] > 0).map((severity) => (
                <span key={severity} className="kpi-breakdown-item">
                  <strong>{severityCounts[severity]}</strong> {severity.toLowerCase()}
                </span>
              ))}
            </div>
          )}
          <Link to="/reconciliation">Review →</Link>
        </div>

        <div className="kpi-card">
          <span className="kpi-label">Latest reconciliation run</span>
          {recentRuns.isPending ? (
            <span className="kpi-value">—</span>
          ) : run ? (
            <>
              <span className="kpi-value kpi-value-compact">{instant(run.startedAt)}</span>
              <span className="kpi-context">
                {run.matched} matched, {run.discrepancies} discrepanc{run.discrepancies === 1 ? 'y' : 'ies'} of{' '}
                {run.internalExamined} examined
              </span>
            </>
          ) : (
            <span className="kpi-value kpi-value-compact">No runs yet</span>
          )}
          <Link to="/reconciliation">Run / review →</Link>
        </div>

        <div className="kpi-card">
          <span className="kpi-label">Model freshness</span>
          {model.isPending ? (
            <span className="kpi-value">—</span>
          ) : model.data ? (
            <>
              <span className="kpi-value kpi-value-compact">{instant(model.data.trainedAt)}</span>
              <span className="kpi-context">ledger snapshot as of {instant(model.data.trainedAsOf)}</span>
            </>
          ) : (
            <span className="kpi-value kpi-value-compact">No model trained</span>
          )}
          <Link to="/model">Detail →</Link>
        </div>

        <div className="kpi-card">
          <span className="kpi-label">Accounts the detector surfaced</span>
          <span className="kpi-value">{anomalies.isPending ? '—' : agreementTotal}</span>
          <span className="kpi-context">statistical composite ≥ 0.50, or model-only elevated</span>
          <Link to="/anomalies">Review →</Link>
        </div>
      </div>

      <div className="grid-two">
        <section className="chart-container">
          <h2 className="chart-title">Open incidents by severity</h2>
          <p className="chart-note">
            <code>GET /reconciliation/incidents?status=OPEN</code>. The one chart on this page allowed a real
            severity colour — reconciliation incidents are the one place that's backend-computed, not a UI
            opinion.
          </p>
          {openIncidentsTotal === 0 ? (
            <p className="th-note">No open incidents right now.</p>
          ) : (
            <div className="bar-chart">
              {SEVERITY_ORDER.filter((severity) => severityCounts[severity] > 0).map((severity) => (
                <div className="bar-row" key={severity}>
                  <span className="bar-row-label">{severity[0]}{severity.slice(1).toLowerCase()}</span>
                  <div className="bar-track">
                    <div
                      className={`bar-fill tone-severity-${severity.toLowerCase()}`}
                      style={{ width: `${(severityCounts[severity] / openIncidentsTotal) * 100}%` }}
                    />
                  </div>
                  <span className="bar-row-count">{severityCounts[severity]}</span>
                </div>
              ))}
            </div>
          )}
        </section>

        <section className="chart-container">
          <h2 className="chart-title">Detection agreement mix</h2>
          <p className="chart-note">
            <code>GET /detection/anomalies</code>, broken down by agreement state. Colour is category, never
            severity — the same rule the ranking page follows.
          </p>
          {agreementTotal === 0 ? (
            <p className="th-note">Nothing flagged right now.</p>
          ) : (
            <div className="bar-chart">
              {Object.entries(agreementCounts).map(([key, count]) => (
                <div className="bar-row" key={key}>
                  <span className="bar-row-label">{AGREEMENT_LABELS[key] ?? key}</span>
                  <div className="bar-track">
                    <div
                      className={`bar-fill ${AGREEMENT_TONE[key] ?? 'cat-neutral'}`}
                      style={{ width: `${(count / agreementTotal) * 100}%` }}
                    />
                  </div>
                  <span className="bar-row-count">{count}</span>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>

      <section className="chart-container chart-container-spaced">
        <h2 className="chart-title">Recent runs — matched vs. examined</h2>
        <p className="chart-note">
          <code>GET /reconciliation/runs</code>, the last {RECENT_RUNS} runs. Bar length is the matched share
          of what each run examined — not a rate the backend computes, just this page reading several real
          runs at once instead of one.
        </p>
        {runs.length === 0 ? (
          <p className="th-note">No runs yet.</p>
        ) : (
          <div className="bar-chart">
            {runs.map((historicRun) => {
              const share = historicRun.internalExamined === 0 ? 0 : historicRun.matched / historicRun.internalExamined;
              return (
                <div className="bar-row" key={historicRun.runId}>
                  <span className="bar-row-label">{instant(historicRun.startedAt)}</span>
                  <div className="bar-track">
                    <div className="bar-fill cat-neutral" style={{ width: `${share * 100}%` }} />
                  </div>
                  <span className="bar-row-count">
                    {historicRun.matched}/{historicRun.internalExamined}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </section>

      <section className="card">
        <h2>Recent activity</h2>
        <p className="card-note">
          Newest first, from <code>GET /accounts</code> and <code>GET /transactions</code> — the same lists
          the ledger pages paginate.
        </p>

        <h3>Accounts</h3>
        {accounts.data && accounts.data.content.length > 0 ? (
          <ul className="activity-list">
            {accounts.data.content.map((account) => (
              <li key={account.id}>
                <strong>{account.name}</strong>
                <span className="activity-meta">
                  <EvidenceLink id={account.id} />
                  <span className="status-pill">{account.currency}</span>
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            icon="accounts"
            title="No accounts yet"
            action={
              <Link className="btn btn-primary" to="/ledger/accounts/new">
                <Icon name="plus" /> Create account
              </Link>
            }
          />
        )}

        <h3>Transactions</h3>
        {transactions.data && transactions.data.content.length > 0 ? (
          <ul className="activity-list">
            {transactions.data.content.map((transaction) => (
              <li key={transaction.id}>
                <strong>{transaction.description}</strong>
                <span className="activity-meta">
                  <EvidenceLink id={transaction.id} />
                  <span className="status-pill">{transaction.currency}</span>
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            icon="transactions"
            title="No transactions yet"
            action={
              <Link className="btn btn-primary" to="/ledger/payments">
                <Icon name="plus" /> Create payment
              </Link>
            }
          />
        )}

        <Link className="activity-more" to="/ledger/accounts">
          Go to ledger →
        </Link>
      </section>
    </div>
  );
}
