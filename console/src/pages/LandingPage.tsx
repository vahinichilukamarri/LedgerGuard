import { Link } from 'react-router-dom';
import { Icon } from '../components/icons/Icon';

/**
 * `/`. The entry layer: what the system is, and one way in. It sits outside
 * the sidebar shell on purpose — the shell is the working surface, and a
 * first-time visitor should meet the product before they meet its navigation.
 *
 * Deliberately no live numbers, cards of destinations, or second call to
 * action: the live figures belong to `/overview`, and every destination is
 * one click further in, in the sidebar that page carries.
 */
export function LandingPage() {
  return (
    <div className="landing">
      <header className="landing-bar">
        <span className="brand-mark" aria-hidden="true">
          LG
        </span>
        <span className="brand-name">LedgerGuard</span>
      </header>

      <main className="landing-hero">
        <span className="section-eyebrow">Ledger integrity and fraud detection</span>
        <h1 className="landing-hero-title">A real ledger, with detection that says what it doesn't know.</h1>
        <p className="landing-hero-sub">
          LedgerGuard is a double-entry payment ledger on Postgres that records payments, refunds and
          reversals, and reconciles every posting against a processor's settlement records. On top of it,
          two independent detection layers, one statistical and one learned, flag unusual accounts, each
          with its own score and neither presented as a calibrated probability.
        </p>
        <p className="landing-hero-sub">
          A blind review queue then measures those flags against human judgement, so the detector is
          checked against something other than its own opinion of itself.
        </p>
        <div className="landing-hero-actions">
          <Link to="/overview" className="btn btn-primary">
            Open dashboard <Icon name="arrow-right" />
          </Link>
        </div>
      </main>

      <footer className="landing-foot">Ops console for the LedgerGuard backend. Every figure is read live.</footer>
    </div>
  );
}
