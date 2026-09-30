import { Link } from 'react-router-dom';
import { Icon, type IconName } from '../components/icons/Icon';

interface DomainCard {
  to: string;
  icon: IconName;
  title: string;
  body: string;
  admin?: boolean;
}

const DOMAINS: DomainCard[] = [
  {
    to: '/ledger/accounts',
    icon: 'ledger',
    title: 'Ledger',
    body: 'Real accounts and transactions. Post a payment, refund it, reverse it — every posting is written to Postgres, nothing here is simulated.',
  },
  {
    to: '/reconciliation',
    icon: 'reconciliation',
    title: 'Reconciliation',
    body: "Compares the ledger against the processor and files anything that disagrees as an incident, with a real backend-computed severity.",
  },
  {
    to: '/anomalies',
    icon: 'anomaly',
    title: 'Detection',
    body: 'Two independent scoring layers, statistical and learned, kept deliberately apart — neither is a calibrated probability, and the console says so on every screen.',
  },
  {
    to: '/validation',
    icon: 'validation',
    title: 'Validation',
    body: 'A blind review queue and a precision/recall report that measure the detector against real reviewer judgement and matured disputes.',
  },
  {
    to: '/simulation',
    icon: 'simulation',
    title: 'Simulation',
    body: 'The one admin surface: inject a settlement fault or a chargeback on demand, so a discrepancy can be produced and reconciled instead of waited for.',
    admin: true,
  },
];

/**
 * `/`. What the system is, before dropping anyone into the dashboard — not a
 * second dashboard itself. Deliberately no live numbers or KPIs here: those
 * belong to `/overview`, and putting them here too would just be a second
 * copy that can go stale independently of the first.
 */
export function LandingPage() {
  return (
    <div className="landing">
      <section className="landing-hero">
        <span className="section-eyebrow">LedgerGuard</span>
        <h1 className="landing-hero-title">A real ledger, with fraud detection that says what it doesn't know.</h1>
        <p className="landing-hero-sub">
          A Postgres-backed double-entry ledger underneath, two independent and explicitly-uncalibrated
          detection layers on top, reconciliation against a simulated processor, and a review pipeline that
          measures the detector against real judgement rather than its own opinion of itself.
        </p>
        <div className="landing-hero-actions">
          <Link to="/overview" className="btn btn-primary">
            Open dashboard <Icon name="arrow-right" />
          </Link>
        </div>
      </section>

      <section>
        <h2 className="landing-section-title">Five things you can actually do here</h2>
        <div className="landing-grid">
          {DOMAINS.map((domain) => (
            <Link
              key={domain.to}
              to={domain.to}
              className={`card card-interactive landing-card${domain.admin ? ' landing-card-admin' : ''}`}
            >
              <span className="landing-card-icon" aria-hidden="true">
                <Icon name={domain.icon} />
              </span>
              <h3>{domain.title}</h3>
              <p className="card-note">{domain.body}</p>
            </Link>
          ))}
        </div>
      </section>
    </div>
  );
}
