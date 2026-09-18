import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { StandingCaveat } from './components/uncertainty/CaveatList';
import { OverviewPage } from './pages/OverviewPage';
import { LedgerPage } from './pages/LedgerPage';
import { ReconciliationPage } from './pages/ReconciliationPage';
import { IncidentDetailPage } from './pages/IncidentDetailPage';
import { AnomaliesPage } from './pages/AnomaliesPage';
import { AccountPage } from './pages/AccountPage';
import { ModelPage } from './pages/ModelPage';
import { SimulationPage } from './pages/SimulationPage';
import { ValidationPage } from './pages/ValidationPage';
import { NotFoundPage } from './pages/NotFoundPage';

/**
 * The console grew from a three-route, read-only detection view into an
 * operations console with write paths of its own. `/simulation` is
 * deliberately last and visually distinct in the nav (its own `nav-admin`
 * styling) — it is the one surface that manipulates a fake processor and
 * card scheme rather than reading or writing the real ledger.
 *
 * Filter, sort and page state lives in the URL rather than in React state, so a
 * reviewer can send someone the view they are actually looking at. That matters
 * more here than usual: "the ML_ONLY rows" is the interesting subset, and a link
 * to it should reproduce it.
 *
 * The header is a normal app shell — a mark, a wordmark, tabbed navigation — and
 * the standing caveat sits underneath it as its own status strip rather than
 * competing with the nav for the same line. Restyling the shell changes none of
 * that caveat's words: it is exactly as long and exactly as unavoidable as it
 * was before, just given a row of its own to be read in.
 */
export function App() {
  return (
    <div className="shell">
      <header className="app-header">
        <div className="app-header-row">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true">
              LG
            </span>
            <div className="brand-text">
              <span className="brand-name">LedgerGuard</span>
              <span className="brand-sub">Ops Console</span>
            </div>
          </div>

          <nav className="app-nav">
            <NavLink to="/overview" className={({ isActive }) => (isActive ? 'on' : '')}>
              Overview
            </NavLink>
            <NavLink to="/ledger" className={({ isActive }) => (isActive ? 'on' : '')}>
              Ledger
            </NavLink>
            <NavLink to="/reconciliation" className={({ isActive }) => (isActive ? 'on' : '')}>
              Reconciliation
            </NavLink>
            <NavLink to="/anomalies" className={({ isActive }) => (isActive ? 'on' : '')}>
              Ranking
            </NavLink>
            <NavLink to="/model" className={({ isActive }) => (isActive ? 'on' : '')}>
              Model
            </NavLink>
            <NavLink to="/validation" className={({ isActive }) => (isActive ? 'on' : '')}>
              Validation
            </NavLink>
            <NavLink
              to="/simulation"
              className={({ isActive }) => (isActive ? 'on nav-admin' : 'nav-admin')}
            >
              Simulation
            </NavLink>
          </nav>

          <span className="header-spacer" />
        </div>

        <StandingCaveat />
      </header>

      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/overview" replace />} />
          <Route path="/overview" element={<OverviewPage />} />
          <Route path="/ledger" element={<LedgerPage />} />
          <Route path="/reconciliation" element={<ReconciliationPage />} />
          <Route path="/reconciliation/incidents/:incidentId" element={<IncidentDetailPage />} />
          <Route path="/anomalies" element={<AnomaliesPage />} />
          <Route path="/accounts/:accountId" element={<AccountPage />} />
          <Route path="/model" element={<ModelPage />} />
          <Route path="/validation" element={<ValidationPage />} />
          <Route path="/simulation" element={<SimulationPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </main>
    </div>
  );
}
