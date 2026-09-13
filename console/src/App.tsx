import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { StandingCaveat } from './components/uncertainty/CaveatList';
import { AnomaliesPage } from './pages/AnomaliesPage';
import { AccountPage } from './pages/AccountPage';
import { ModelPage } from './pages/ModelPage';
import { NotFoundPage } from './pages/NotFoundPage';

/**
 * Three routes and a standing caveat.
 *
 * The route list is short on purpose. Every screen here answers one of three
 * questions — which accounts did the detector surface, why did it surface this
 * one, and which model produced the second opinion — and a fourth screen would
 * mean the console had started to have opinions of its own.
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
            <NavLink to="/anomalies" className={({ isActive }) => (isActive ? 'on' : '')}>
              Ranking
            </NavLink>
            <NavLink to="/model" className={({ isActive }) => (isActive ? 'on' : '')}>
              Model
            </NavLink>
          </nav>

          <span className="header-spacer" />
          <span className="badge-static">Read-only</span>
        </div>

        <StandingCaveat />
      </header>

      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/anomalies" replace />} />
          <Route path="/anomalies" element={<AnomaliesPage />} />
          <Route path="/accounts/:accountId" element={<AccountPage />} />
          <Route path="/model" element={<ModelPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </main>
    </div>
  );
}
