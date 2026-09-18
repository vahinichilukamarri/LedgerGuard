import { DisputeInjectionForm } from '../components/simulation/DisputeInjectionForm';
import { DisputesTable } from '../components/simulation/DisputesTable';
import { FaultInjectionForm } from '../components/simulation/FaultInjectionForm';
import { SettlementRecordsTable } from '../components/simulation/SettlementRecordsTable';

/**
 * The simulated processor and card scheme — `/admin/settlement/*` and
 * `/admin/disputes`. Deliberately its own route, its own visual treatment,
 * and its own confirmation flow, never mixed into `/ledger` or
 * `/reconciliation`: everything here manufactures a disagreement or a
 * chargeback on demand, and nothing here is real money moving.
 */
export function SimulationPage() {
  return (
    <div>
      <h1>Simulation</h1>
      <div className="admin-banner" role="status">
        Admin / chaos surface. Nothing on this page touches the real ledger — it manipulates the simulated
        processor and card scheme so a discrepancy or a chargeback can be produced on demand instead of
        waited for.
      </div>

      <section className="card admin-zone">
        <h2>Inject a settlement fault</h2>
        <p className="card-note">
          <code>POST /admin/settlement/faults</code>. Each fault type maps to exactly one discrepancy type the
          next reconciliation run should report.
        </p>
        <FaultInjectionForm />
      </section>

      <section className="card">
        <h2>Settlement records</h2>
        <p className="card-note">
          <code>GET /admin/settlement/records</code> — what the simulated processor currently believes.
        </p>
        <SettlementRecordsTable />
      </section>

      <section className="card admin-zone">
        <h2>Raise a dispute</h2>
        <p className="card-note">
          <code>POST /admin/disputes</code>. Only <code>FRAUDULENT</code> ever becomes a fraud label — see the
          reasoning on the form.
        </p>
        <DisputeInjectionForm />
      </section>

      <section className="card">
        <h2>Disputes raised</h2>
        <p className="card-note">
          <code>GET /admin/disputes</code> — what the simulated scheme currently believes.
        </p>
        <DisputesTable />
      </section>
    </div>
  );
}
