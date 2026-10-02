import { Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { ThemeToggle } from './components/ThemeToggle';
import { StandingCaveat } from './components/uncertainty/CaveatList';
import { Icon, type IconName } from './components/icons/Icon';
import { LandingPage } from './pages/LandingPage';
import { OverviewPage } from './pages/OverviewPage';
import { LedgerAccountsPage } from './pages/LedgerAccountsPage';
import { LedgerCreateAccountPage } from './pages/LedgerCreateAccountPage';
import { LedgerPaymentsPage } from './pages/LedgerPaymentsPage';
import { LedgerTransactionsPage } from './pages/LedgerTransactionsPage';
import { ReconciliationPage } from './pages/ReconciliationPage';
import { IncidentDetailPage } from './pages/IncidentDetailPage';
import { AnomaliesPage } from './pages/AnomaliesPage';
import { AccountPage } from './pages/AccountPage';
import { ModelPage } from './pages/ModelPage';
import { SimulationPage } from './pages/SimulationPage';
import { ValidationPage } from './pages/ValidationPage';
import { NotFoundPage } from './pages/NotFoundPage';

type NavLeaf = { to: string; label: string; icon: IconName };
type NavItem = NavLeaf & { children?: NavLeaf[] };

/**
 * Grouped the way a reviewer thinks about the system: what it does, what it
 * found, and the one admin surface. `Ledger` is the one item with children —
 * its own label, not itself a link, with the four separate ledger sections
 * indented underneath it. Every other item stays a single flat link.
 */
const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: 'Operations',
    items: [
      { to: '/overview', label: 'Overview', icon: 'overview' },
      {
        to: '/ledger',
        label: 'Ledger',
        icon: 'ledger',
        children: [
          { to: '/ledger/accounts', label: 'Accounts', icon: 'accounts' },
          { to: '/ledger/accounts/new', label: 'Create account', icon: 'create' },
          { to: '/ledger/payments', label: 'Payments', icon: 'payments' },
          { to: '/ledger/transactions', label: 'Transactions', icon: 'transactions' },
        ],
      },
      { to: '/reconciliation', label: 'Reconciliation', icon: 'reconciliation' },
    ],
  },
  {
    label: 'Detection',
    items: [
      { to: '/anomalies', label: 'Anomaly detection', icon: 'anomaly' },
      { to: '/model', label: 'Model', icon: 'model' },
      { to: '/validation', label: 'Validation', icon: 'validation' },
    ],
  },
];

const ADMIN_ITEM: NavLeaf = { to: '/simulation', label: 'Simulation', icon: 'simulation' };

/**
 * A sidebar shell, replacing Phase 14's top-nav header now that the console
 * has a real, separated Ledger domain instead of one crowded page. The
 * sidebar is the fixed, always-dark landmark described in `styles.css`'s
 * sidebar tokens — it does not follow the content area's own light/dark
 * mode, so "where am I" stays answerable even while a page's own theme is
 * mid-switch.
 *
 * `/simulation` sits below a divider with its own `nav-admin` styling, in
 * both the sidebar and the content it routes to: it is the one section that
 * manipulates a simulated processor and card scheme rather than the real
 * ledger, and the hard rule for this project is that admin/chaos surfaces
 * stay visually and navigationally separated with explicit confirmation
 * before they fire.
 *
 * The standing caveat is not in the sidebar. It is a statement about the
 * *content* — "detection scores are unvalidated" — so it stays with the
 * content area, as its own sticky strip above whichever page is open, rather
 * than being demoted into a rail that is otherwise pure navigation.
 *
 * Filter, sort and page state lives in the URL rather than in React state, so a
 * reviewer can send someone the view they are actually looking at. That matters
 * more here than usual: "the ML_ONLY rows" is the interesting subset, and a link
 * to it should reproduce it.
 */
function Shell() {
  const { pathname } = useLocation();
  return (
    <div className="shell-layout">
      <aside className="app-sidebar">
        <Link to="/" className="sidebar-brand">
          <span className="brand-mark" aria-hidden="true">
            LG
          </span>
          <div className="brand-text">
            <span className="brand-name">LedgerGuard</span>
            <span className="brand-sub">Ops Console</span>
          </div>
        </Link>

        <nav className="sidebar-nav" aria-label="Sections">
          {NAV_GROUPS.map((group) => (
            <div key={group.label}>
              <div className="sidebar-nav-label">{group.label}</div>
              {group.items.map((item) =>
                item.children ? (
                  <div key={item.to} className="sidebar-nav-subgroup">
                    <div className="sidebar-nav-sublabel">
                      <Icon name={item.icon} /> {item.label}
                    </div>
                    {item.children.map((child) => (
                      <NavLink
                        key={child.to}
                        to={child.to}
                        end
                        className={({ isActive }) => `sub${isActive ? ' on' : ''}`}
                      >
                        <Icon name={child.icon} /> {child.label}
                      </NavLink>
                    ))}
                  </div>
                ) : (
                  <NavLink key={item.to} to={item.to} className={({ isActive }) => (isActive ? 'on' : '')}>
                    <Icon name={item.icon} /> {item.label}
                  </NavLink>
                ),
              )}
            </div>
          ))}

          <div className="sidebar-nav-divider" />
          <div className="sidebar-nav-label">Admin</div>
          <NavLink to={ADMIN_ITEM.to} className={({ isActive }) => (isActive ? 'on nav-admin' : 'nav-admin')}>
            <Icon name={ADMIN_ITEM.icon} /> {ADMIN_ITEM.label}
          </NavLink>
        </nav>

        <div className="sidebar-foot">
          <span>Live from the backend.</span>
          <ThemeToggle />
        </div>
      </aside>

      <div className="app-content">
        <div className="content-caveat-bar">
          <StandingCaveat />
        </div>

        <main className="content-inner">
          <div key={pathname} className="page-enter">
            <Routes>
              <Route path="/overview" element={<OverviewPage />} />
              <Route path="/ledger" element={<Navigate to="/ledger/accounts" replace />} />
              <Route path="/ledger/accounts" element={<LedgerAccountsPage />} />
              <Route path="/ledger/accounts/new" element={<LedgerCreateAccountPage />} />
              <Route path="/ledger/payments" element={<LedgerPaymentsPage />} />
              <Route path="/ledger/transactions" element={<LedgerTransactionsPage />} />
              <Route path="/reconciliation" element={<ReconciliationPage />} />
              <Route path="/reconciliation/incidents/:incidentId" element={<IncidentDetailPage />} />
              <Route path="/anomalies" element={<AnomaliesPage />} />
              <Route path="/accounts/:accountId" element={<AccountPage />} />
              <Route path="/model" element={<ModelPage />} />
              <Route path="/validation" element={<ValidationPage />} />
              <Route path="/simulation" element={<SimulationPage />} />
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </div>
        </main>
      </div>
    </div>
  );
}

/**
 * Three layers. `/` is the landing page, deliberately outside the shell so the
 * first thing anyone sees is the product rather than its navigation; every
 * other path — the dashboard and each functional page — lives inside `Shell`.
 */
export function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/*" element={<Shell />} />
    </Routes>
  );
}
