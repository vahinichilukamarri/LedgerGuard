import type { CSSProperties, PointerEvent as ReactPointerEvent } from 'react';
import { Link } from 'react-router-dom';
import { Icon, type IconName } from '../components/icons/Icon';
import { Reveal } from '../components/motion/Reveal';
import { ThemeToggle } from '../components/ThemeToggle';

interface Feature {
  icon: IconName;
  title: string;
  body: string;
}

const FEATURES: Feature[] = [
  {
    icon: 'ledger',
    title: 'A double-entry ledger',
    body: 'Every payment posts a balanced pair of entries per currency. Nothing is written unless it nets to zero, and balances are derived from postings, never stored.',
  },
  {
    icon: 'lock',
    title: 'Idempotent by construction',
    body: 'A replayed payment, refund or reversal has exactly one financial effect. Refunds are capped at what remains, and a transaction can be reversed once.',
  },
  {
    icon: 'reconciliation',
    title: 'Reconciliation you can audit',
    body: 'The ledger is compared with a processor’s settlement records. Every disagreement becomes an incident with a backend-computed severity and both sides of the evidence.',
  },
  {
    icon: 'activity',
    title: 'Two independent detectors',
    body: 'A statistical layer and an isolation forest score accounts separately. They are shown side by side, never blended into one number nobody could defend.',
  },
  {
    icon: 'eye',
    title: 'Explanations, not verdicts',
    body: 'Each flag comes with the signals that fired, the features the model isolated on, and the caveats that limit what the score can claim.',
  },
  {
    icon: 'shield',
    title: 'Validated against people',
    body: 'A blind review queue and matured disputes measure precision and recall against real judgement, so the detector is checked by something other than itself.',
  },
];

const STEPS: { title: string; body: string }[] = [
  { title: 'Post', body: 'Payments, refunds and reversals land in Postgres as balanced transactions.' },
  { title: 'Reconcile', body: 'Each run compares the ledger with settlement records and files what differs.' },
  { title: 'Detect', body: 'Two layers score every account and say which one, or both, raised it.' },
  { title: 'Validate', body: 'Reviewers label blind, and the report says how good the flags really are.' },
];

const TECH = [
  'PostgreSQL',
  'Apache Kafka',
  'Spring Boot',
  'Transactional outbox',
  'Isolation forest',
  'Idempotency keys',
  'Testcontainers',
  'Property-based tests',
];

/** Lets a card's glow follow the pointer without re-rendering anything. */
function trackPointer(event: ReactPointerEvent<HTMLElement>) {
  const box = event.currentTarget.getBoundingClientRect();
  event.currentTarget.style.setProperty('--mx', `${event.clientX - box.left}px`);
  event.currentTarget.style.setProperty('--my', `${event.clientY - box.top}px`);
}

/** Pure illustration: shapes standing in for a dashboard. No figures, so nothing here can be mistaken for data. */
function HeroPreview() {
  return (
    <div className="preview" aria-hidden="true">
      <div className="preview-glow" />
      <div className="preview-window">
        <div className="preview-bar">
          <i />
          <i />
          <i />
          <span />
        </div>
        <div className="preview-body">
          <div className="preview-side">
            {[62, 44, 54, 38, 50, 34].map((w, i) => (
              <span key={i} style={{ width: `${w}%`, '--i': i } as CSSProperties} />
            ))}
          </div>
          <div className="preview-main">
            <div className="preview-kpis">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="preview-kpi" style={{ '--i': i } as CSSProperties}>
                  <span />
                  <b />
                </div>
              ))}
            </div>
            <div className="preview-row">
              <div className="preview-chart">
                <svg viewBox="0 0 300 110" preserveAspectRatio="none">
                  <defs>
                    <linearGradient id="pv-fill" x1="0" x2="0" y1="0" y2="1">
                      <stop offset="0" stopColor="currentColor" stopOpacity="0.35" />
                      <stop offset="1" stopColor="currentColor" stopOpacity="0" />
                    </linearGradient>
                  </defs>
                  <path
                    className="preview-area"
                    d="M0 92 C30 88 45 70 75 72 S120 40 150 48 S200 22 230 28 S275 10 300 8 L300 110 L0 110Z"
                    fill="url(#pv-fill)"
                  />
                  <path
                    className="preview-line"
                    pathLength={1}
                    d="M0 92 C30 88 45 70 75 72 S120 40 150 48 S200 22 230 28 S275 10 300 8"
                  />
                </svg>
              </div>
              <div className="preview-donut">
                <svg viewBox="0 0 120 120">
                  <circle cx="60" cy="60" r="44" className="pd-track" />
                  <circle cx="60" cy="60" r="44" className="pd-a" pathLength={100} />
                  <circle cx="60" cy="60" r="44" className="pd-b" pathLength={100} />
                </svg>
              </div>
            </div>
            <div className="preview-table">
              {[0, 1, 2].map((i) => (
                <div key={i} style={{ '--i': i } as CSSProperties}>
                  <span />
                  <span />
                  <span />
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
      <span className="preview-chip preview-chip-a">
        <Icon name="check" /> Balanced
      </span>
      <span className="preview-chip preview-chip-b">
        <Icon name="shield" /> Two scores, never blended
      </span>
    </div>
  );
}

/**
 * `/`. The entry layer, outside the app shell: a visitor meets the product
 * before its navigation. Nothing here reads live data — every live figure
 * belongs to the dashboard — so the page can never show a number that has gone
 * stale, and the preview is deliberately abstract.
 */
export function LandingPage() {
  return (
    <div className="landing">
      <div className="landing-aurora" aria-hidden="true">
        <span className="orb orb-a" />
        <span className="orb orb-b" />
        <span className="orb orb-c" />
        <span className="landing-grid" />
      </div>

      <header className="landing-nav">
        <Link to="/" className="landing-brand" aria-label="LedgerGuard home">
          <span className="brand-mark" aria-hidden="true">
            LG
          </span>
          <span className="brand-name">LedgerGuard</span>
        </Link>
        <nav className="landing-links" aria-label="Page sections">
          <a href="#features">Features</a>
          <a href="#how">How it works</a>
          <a href="#principles">Principles</a>
        </nav>
        <div className="landing-nav-actions">
          <ThemeToggle />
          <Link to="/overview" className="btn btn-primary btn-sm">
            Open dashboard <Icon name="arrow-right" />
          </Link>
        </div>
      </header>

      <main>
        <section className="landing-hero">
          <span className="hero-pill enter" style={{ '--d': '0ms' } as CSSProperties}>
            <span className="hero-pill-dot" /> Ledger integrity &amp; anomaly detection
          </span>
          <h1 className="landing-hero-title enter" style={{ '--d': '80ms' } as CSSProperties}>
            A ledger you can trust,
            <br />
            and a detector that <span className="grad-text">admits what it doesn’t know.</span>
          </h1>
          <p className="landing-hero-sub enter" style={{ '--d': '160ms' } as CSSProperties}>
            LedgerGuard is a double-entry payment ledger that reconciles every posting against a processor’s
            settlement records. On top of it, two independent detection layers flag unusual accounts, each with
            its own score, and a blind review queue checks them against human judgement.
          </p>
          <div className="landing-hero-actions enter" style={{ '--d': '240ms' } as CSSProperties}>
            <Link to="/overview" className="btn btn-primary btn-lg">
              Open dashboard <Icon name="arrow-right" />
            </Link>
            <a href="#how" className="btn btn-ghost btn-lg">
              See how it works
            </a>
          </div>
          <div className="enter" style={{ '--d': '360ms' } as CSSProperties}>
            <HeroPreview />
          </div>
        </section>

        <div className="marquee" aria-hidden="true">
          <div className="marquee-track">
            {[...TECH, ...TECH].map((item, i) => (
              <span key={i} className="marquee-item">
                <i /> {item}
              </span>
            ))}
          </div>
        </div>

        <section id="features" className="landing-section">
          <Reveal>
            <span className="section-eyebrow">What it does</span>
            <h2 className="landing-h2">Everything between a payment and a judgement</h2>
            <p className="landing-lede">
              One system that records money movement, checks it against the outside world, and is honest about how
              much its own alarms are worth.
            </p>
          </Reveal>
          <div className="feature-grid">
            {FEATURES.map((feature, i) => (
              <Reveal key={feature.title} delay={i * 70}>
                <article className="feature-card" onPointerMove={trackPointer}>
                  <span className="feature-icon">
                    <Icon name={feature.icon} />
                  </span>
                  <h3>{feature.title}</h3>
                  <p>{feature.body}</p>
                </article>
              </Reveal>
            ))}
          </div>
        </section>

        <section id="how" className="landing-section">
          <Reveal>
            <span className="section-eyebrow">How it works</span>
            <h2 className="landing-h2">From a posting to a validated finding</h2>
          </Reveal>
          <ol className="steps">
            {STEPS.map((step, i) => (
              <Reveal key={step.title} as="li" delay={i * 110} className="step">
                <span className="step-num">{i + 1}</span>
                <h3>{step.title}</h3>
                <p>{step.body}</p>
              </Reveal>
            ))}
          </ol>
        </section>

        <section id="principles" className="landing-section">
          <Reveal>
            <div className="principles">
              <div>
                <span className="section-eyebrow">Principles</span>
                <h2 className="landing-h2">Built to say what it doesn’t know</h2>
                <p className="landing-lede">
                  Detection tooling tends to look more certain than it is. This one is designed the other way around.
                </p>
              </div>
              <ul className="principle-list">
                <li>
                  <Icon name="layers" />
                  <div>
                    <strong>No blended score.</strong> The two layers stay apart, because averaging them would imply a
                    reconciliation nothing here has earned.
                  </div>
                </li>
                <li>
                  <Icon name="shield" />
                  <div>
                    <strong>Scores are labelled unvalidated.</strong> Until they are measured against ground truth, the
                    console says so on every screen.
                  </div>
                </li>
                <li>
                  <Icon name="activity" />
                  <div>
                    <strong>Every figure is read live.</strong> Nothing on the dashboard is a placeholder; each number
                    is the direct result of the endpoint behind it.
                  </div>
                </li>
              </ul>
            </div>
          </Reveal>
        </section>

        <section className="landing-section">
          <Reveal>
            <div className="cta-card">
              <h2>See it running on real data</h2>
              <p>The dashboard opens straight onto the live ledger, reconciliation runs and detection results.</p>
              <Link to="/overview" className="btn btn-light btn-lg">
                Open dashboard <Icon name="arrow-right" />
              </Link>
            </div>
          </Reveal>
        </section>
      </main>

      <footer className="landing-foot">
        <span className="brand-mark" aria-hidden="true">
          LG
        </span>
        <span>LedgerGuard — ledger integrity and anomaly detection ops console.</span>
      </footer>
    </div>
  );
}
