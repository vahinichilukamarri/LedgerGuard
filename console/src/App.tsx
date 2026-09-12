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
 */
export function App() {
  return (
    <div className="shell">
      <header className="masthead">
        <h1>LedgerGuard — Ops Console</h1>
        <nav>
          <NavLink to="/anomalies" className={({ isActive }) => (isActive ? 'on' : '')}>
            Ranking
          </NavLink>
          <NavLink to="/model" className={({ isActive }) => (isActive ? 'on' : '')}>
            Model
          </NavLink>
        </nav>
        <span className="spacer" />
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
