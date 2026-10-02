import { useMemo, type CSSProperties, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  useAccounts,
  useAnomalies,
  useIncidents,
  useModel,
  useReconciliationRuns,
  useTransactions,
} from '../api/queries';
import type { Severity } from '../api/types';
import { instant } from '../format';
import { AreaChart, type AreaPoint } from '../components/charts/AreaChart';
import { ColumnChart, type Column } from '../components/charts/ColumnChart';
import { Donut, type DonutDatum } from '../components/charts/Donut';
import { Sparkline } from '../components/charts/Sparkline';
import { EmptyState } from '../components/EmptyState';
import { Icon, type IconName } from '../components/icons/Icon';
import { CountUp } from '../components/motion/CountUp';
import { Reveal } from '../components/motion/Reveal';
import { PageHeader } from '../components/PageHeader';
import { EvidenceLink } from '../components/ledger/EvidenceLink';

const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

const AGREEMENT: { key: string; label: string; color: string }[] = [
  { key: 'BOTH_ELEVATED', label: 'Both elevated', color: 'var(--cat-both)' },
  { key: 'STATISTICAL_ONLY', label: 'Statistical only', color: 'var(--cat-statistical)' },
  { key: 'ML_ONLY', label: 'Model only', color: 'var(--cat-model)' },
  { key: 'BOTH_QUIET', label: 'Both quiet', color: 'var(--cat-neutral)' },
  { key: 'NO_MODEL', label: 'No model to compare', color: 'var(--border-strong)' },
];

const RECENT_RUNS = 8;
const SAMPLE = 100;
const BINS = 10;

function histogram(values: number[], name: string, color: string): Column[] {
  const counts = new Array<number>(BINS).fill(0);
  values.forEach((v) => {
    const bin = Math.min(BINS - 1, Math.max(0, Math.floor(v * BINS)));
    counts[bin] = (counts[bin] ?? 0) + 1;
  });
  return counts.map((count, i) => ({
    label: (i / BINS).toFixed(1),
    title: `${name} ${(i / BINS).toFixed(1)}–${((i + 1) / BINS).toFixed(1)}`,
    segments: [{ key: name, label: 'accounts', value: count, color }],
  }));
}

/** Cumulative count over time, anchored so the last point equals the true total even when only the newest page was fetched. */
function cumulative(dates: string[], total: number): AreaPoint[] {
  const times = dates.map((d) => new Date(d).getTime()).sort((a, b) => a - b);
  return times.map((t, i) => ({ t, y: total - (times.length - 1 - i) }));
}

function initials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');
}

function StatCard({
  icon,
  label,
  value,
  suffix,
  decimals,
  pending,
  note,
  trend,
  to,
  tone,
  index,
}: {
  icon: IconName;
  label: string;
  value: number | null;
  suffix?: string;
  decimals?: number;
  pending: boolean;
  note?: ReactNode;
  trend?: number[];
  to: string;
  tone?: string;
  index: number;
}) {
  return (
    <Link
      to={to}
      className={`stat-card${tone ? ` ${tone}` : ''}`}
      style={{ '--i': index } as CSSProperties}
    >
      <div className="stat-card-head">
        <span className="stat-card-icon" aria-hidden="true">
          <Icon name={icon} />
        </span>
        <span className="stat-card-label">{label}</span>
        <Icon name="arrow-right" className="stat-card-go" />
      </div>
      <div className="stat-card-value">
        {pending || value === null ? '—' : <CountUp value={value} suffix={suffix} decimals={decimals} />}
      </div>
      <div className="stat-card-foot">
        <span className="stat-card-note">{note}</span>
        {trend && <Sparkline values={trend} />}
      </div>
    </Link>
  );
}

function ChartCard({
  title,
  note,
  children,
  className = '',
  delay = 0,
}: {
  title: string;
  note: ReactNode;
  children: ReactNode;
  className?: string;
  delay?: number;
}) {
  return (
    <Reveal delay={delay} className={`chart-card-wrap ${className}`}>
      <section className="chart-card">
        <header>
          <h2>{title}</h2>
          <p>{note}</p>
        </header>
        {children}
      </section>
    </Reveal>
  );
}

/**
 * No dashboard/summary endpoint exists on the backend — this page composes
 * six independent, already-existing reads, run in parallel, so each figure
 * is accurate as of its own request and two of them can be a moment apart.
 * Every number and every mark below is the direct result of one of those
 * reads; the charts draw only what the API returned. Where a chart needs a
 * time series (cumulative activity, run history) it is built from the
 * timestamps the records already carry.
 */
export function OverviewPage() {
  const openIncidents = useIncidents({ status: 'OPEN' });
  const recentRuns = useReconciliationRuns(0, RECENT_RUNS);
  const anomalies = useAnomalies(0, true);
  const model = useModel();
  const accounts = useAccounts(0, SAMPLE);
  const transactions = useTransactions(0, SAMPLE);

  const incidents = openIncidents.data ?? [];
  const severityCounts = Object.fromEntries(SEVERITY_ORDER.map((s) => [s, 0])) as Record<Severity, number>;
  incidents.forEach((incident) => {
    severityCounts[incident.severity] += 1;
  });
  const worstSeverity = SEVERITY_ORDER.find((severity) => severityCounts[severity] > 0);

  const scored = anomalies.data ?? [];
  const agreementCounts: Record<string, number> = {};
  scored.forEach((row) => {
    const key = row.explanation?.agreement ?? 'NO_MODEL';
    agreementCounts[key] = (agreementCounts[key] ?? 0) + 1;
  });
  const ELEVATED = ['BOTH_ELEVATED', 'STATISTICAL_ONLY', 'ML_ONLY'];
  const surfaced = scored.filter((row) => ELEVATED.includes(row.explanation?.agreement ?? '')).length;

  const runs = useMemo(() => [...(recentRuns.data?.content ?? [])].reverse(), [recentRuns.data]);
  const latest = runs[runs.length - 1];
  const matchRate = latest && latest.internalExamined > 0 ? (latest.matched / latest.internalExamined) * 100 : null;

  const accountTotal = accounts.data?.totalElements ?? 0;
  const transactionTotal = transactions.data?.totalElements ?? 0;
  const txSeries = useMemo(
    () => cumulative((transactions.data?.content ?? []).map((t) => t.createdAt), transactionTotal),
    [transactions.data, transactionTotal],
  );
  const accountSeries = useMemo(
    () => cumulative((accounts.data?.content ?? []).map((a) => a.createdAt), accountTotal),
    [accounts.data, accountTotal],
  );

  const severityData: DonutDatum[] = SEVERITY_ORDER.slice()
    .reverse()
    .map((severity) => ({
      key: severity,
      label: severity[0] + severity.slice(1).toLowerCase(),
      value: severityCounts[severity],
      color: `var(--severity-${severity.toLowerCase()})`,
    }));
  const agreementData: DonutDatum[] = AGREEMENT.map((a) => ({ ...a, value: agreementCounts[a.key] ?? 0 }));

  const runColumns: Column[] = runs.map((run) => ({
    label: new Date(run.startedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
    title: `Run ${instant(run.startedAt)}`,
    segments: [
      { key: 'matched', label: 'Matched', value: run.matched, color: 'var(--accent)' },
      { key: 'discrepancies', label: 'Discrepancies', value: run.discrepancies, color: 'var(--context-ink)' },
    ],
  }));

  const statisticalScores = scored.map((row) => row.statisticalScore).filter((v): v is number => typeof v === 'number');
  const modelScores = scored.map((row) => row.ml?.score).filter((v): v is number => typeof v === 'number');

  return (
    <div className="overview">
      <PageHeader
        icon="overview"
        title="Overview"
        description="The ledger, its reconciliation and both detection layers at a glance. Every figure is read live from its own endpoint, so two tiles can be a moment apart."
        action={
          <>
            <Link className="btn btn-secondary" to="/ledger/payments">
              <Icon name="plus" /> New payment
            </Link>
            <Link className="btn btn-primary" to="/reconciliation">
              <Icon name="refresh" /> Run reconciliation
            </Link>
          </>
        }
      />

      <div className="stat-grid">
        <StatCard
          index={0}
          icon="accounts"
          label="Accounts"
          to="/ledger/accounts"
          value={accountTotal}
          pending={accounts.isPending}
          note="in the ledger"
          trend={accountSeries.map((p) => p.y)}
        />
        <StatCard
          index={1}
          icon="transactions"
          label="Transactions posted"
          to="/ledger/transactions"
          value={transactionTotal}
          pending={transactions.isPending}
          note="balanced entries"
          trend={txSeries.map((p) => p.y)}
        />
        <StatCard
          index={2}
          icon="reconciliation"
          label="Open reconciliation incidents"
          to="/reconciliation"
          value={openIncidents.isPending ? null : incidents.length}
          pending={openIncidents.isPending}
          tone={worstSeverity ? `stat-severity-${worstSeverity.toLowerCase()}` : undefined}
          note={
            incidents.length > 0 ? (
              <span className="stat-breakdown">
                {SEVERITY_ORDER.filter((s) => severityCounts[s] > 0).map((s) => (
                  <span key={s}>
                    <strong>{severityCounts[s]}</strong> {s.toLowerCase()}
                  </span>
                ))}
              </span>
            ) : (
              'nothing open'
            )
          }
        />
        <StatCard
          index={3}
          icon="check"
          label="Latest run matched"
          to="/reconciliation"
          value={matchRate}
          decimals={0}
          suffix="%"
          pending={recentRuns.isPending}
          note={
            latest
              ? `${latest.matched} matched, ${latest.discrepancies} discrepanc${latest.discrepancies === 1 ? 'y' : 'ies'} of ${latest.internalExamined} examined`
              : 'No runs yet'
          }
          trend={runs.map((r) => (r.internalExamined ? (r.matched / r.internalExamined) * 100 : 0))}
        />
      </div>

      <div className="chart-row chart-row-wide">
        <ChartCard
          title="Ledger activity"
          note={<>Cumulative transactions posted, from <code>GET /transactions</code>.</>}
        >
          <AreaChart points={txSeries} unit="transactions" />
        </ChartCard>
        <ChartCard
          title="Open incidents by severity"
          note="Reconciliation incidents carry a backend-computed severity, the one place this console colours by it."
          delay={80}
        >
          <Donut data={severityData} centerLabel="open" emptyLabel="No open incidents" />
        </ChartCard>
      </div>

      <div className="chart-row">
        <ChartCard
          title="Reconciliation runs"
          note={<>The last {RECENT_RUNS} runs from <code>GET /reconciliation/runs</code>: what matched and what did not.</>}
        >
          <ColumnChart columns={runColumns} unit="records examined" />
        </ChartCard>
        <ChartCard
          title="Detection agreement"
          note="Where the two layers agree and where only one speaks. Colour is category, never severity."
          delay={80}
        >
          <Donut data={agreementData} centerLabel="accounts scored" emptyLabel="Nothing scored yet" />
        </ChartCard>
      </div>

      <div className="chart-row">
        <ChartCard
          title="Statistical composite, by account"
          note="Distribution of the statistical layer’s scores. A convention, not a calibrated probability."
        >
          <ColumnChart columns={histogram(statisticalScores, 'Statistical', 'var(--cat-statistical)')} unit="accounts" legend={false} />
        </ChartCard>
        <ChartCard
          title="Isolation score, by account"
          note="Distribution of the model layer’s scores, shown apart from the statistical one on purpose."
          delay={80}
        >
          {modelScores.length > 0 ? (
            <ColumnChart columns={histogram(modelScores, 'Isolation', 'var(--cat-model)')} unit="accounts" legend={false} />
          ) : (
            <EmptyState icon="model" title="No model trained">
              Without a model there is no second score to plot.{' '}
              <Link to="/model">Train one on the Model page.</Link>
            </EmptyState>
          )}
        </ChartCard>
      </div>

      <div className="chart-row chart-row-even">
        <Reveal className="chart-card-wrap">
          <section className="chart-card">
            <header>
              <h2>Newest accounts</h2>
              <p>
                From <code>GET /accounts</code>.
              </p>
            </header>
            {accounts.data && accounts.data.content.length > 0 ? (
              <ul className="activity-list">
                {accounts.data.content.slice(0, 6).map((account) => (
                  <li key={account.id}>
                    <span className="avatar" aria-hidden="true">
                      {initials(account.name)}
                    </span>
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
            <Link className="activity-more" to="/ledger/accounts">
              All accounts <Icon name="arrow-right" />
            </Link>
          </section>
        </Reveal>

        <Reveal className="chart-card-wrap" delay={80}>
          <section className="chart-card">
            <header>
              <h2>Latest transactions</h2>
              <p>
                From <code>GET /transactions</code>, newest first.
              </p>
            </header>
            {transactions.data && transactions.data.content.length > 0 ? (
              <ul className="activity-list">
                {transactions.data.content.slice(0, 6).map((transaction) => (
                  <li key={transaction.id}>
                    <span className="avatar avatar-tx" aria-hidden="true">
                      <Icon name="transactions" />
                    </span>
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
            <Link className="activity-more" to="/ledger/transactions">
              All transactions <Icon name="arrow-right" />
            </Link>
          </section>
        </Reveal>
      </div>

      <Reveal>
        <div className="model-strip">
          <span className="model-strip-icon" aria-hidden="true">
            <Icon name="model" />
          </span>
          <div>
            <strong>{model.isPending ? 'Checking the model…' : model.data ? 'Model loaded' : 'No model trained'}</strong>
            <span>
              {model.data
                ? `Trained ${instant(model.data.trainedAt)} on ${model.data.trainingAccounts} accounts. ${surfaced} of ${scored.length} scored accounts were raised by at least one layer.`
                : 'Every account will show one layer only until a model is trained.'}
            </span>
          </div>
          <Link className="btn btn-secondary" to="/model">
            Model detail <Icon name="arrow-right" />
          </Link>
        </div>
      </Reveal>
    </div>
  );
}
