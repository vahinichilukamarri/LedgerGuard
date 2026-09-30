import { PageHeader } from '../components/PageHeader';
import { RunHistoryPanel } from '../components/reconciliation/RunHistoryPanel';
import { IncidentsPanel } from '../components/reconciliation/IncidentsPanel';

export function ReconciliationPage() {
  return (
    <div>
      <PageHeader
        icon="reconciliation"
        title="Reconciliation"
        description="Compares the ledger against the simulated processor and files anything that disagrees as an incident."
      />
      <RunHistoryPanel />
      <IncidentsPanel />
    </div>
  );
}
