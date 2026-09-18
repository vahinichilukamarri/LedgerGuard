import { RunHistoryPanel } from '../components/reconciliation/RunHistoryPanel';
import { IncidentsPanel } from '../components/reconciliation/IncidentsPanel';

export function ReconciliationPage() {
  return (
    <div>
      <h1>Reconciliation</h1>
      <p className="card-note" style={{ marginBottom: 20 }}>
        Compares the ledger against the simulated processor and files anything that disagrees as an incident.
      </p>
      <RunHistoryPanel />
      <IncidentsPanel />
    </div>
  );
}
