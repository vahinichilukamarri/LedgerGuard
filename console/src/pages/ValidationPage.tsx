import { PageHeader } from '../components/PageHeader';
import { CensusPanel } from '../components/validation/CensusPanel';
import { ReviewQueuePanel } from '../components/validation/ReviewQueuePanel';
import { ValidationReportPanel } from '../components/validation/ValidationReportPanel';

export function ValidationPage() {
  return (
    <div>
      <PageHeader
        icon="validation"
        title="Validation"
        description={
          <>
            Where the detector is measured against real judgement — reviewer verdicts and matured disputes —
            not where the detector's opinion is displayed. See <code>/anomalies</code> and{' '}
            <code>/accounts/:id</code> for that.
          </>
        }
      />

      <section className="card">
        <h2>Review queue</h2>
        <p className="card-note">
          <code>GET /validation/review/next</code>, <code>POST /validation/labels</code>. Blind by default —
          see the note on the form for why.
        </p>
        <ReviewQueuePanel />
      </section>

      <section className="card">
        <h2>Review census</h2>
        <p className="card-note">
          <code>GET /validation/review/census</code>. How many accounts sit in each pool, which is what makes
          the sampling weight checkable.
        </p>
        <CensusPanel />
      </section>

      <section className="card">
        <h2>Precision / recall report</h2>
        <p className="card-note">
          <code>GET /validation/report</code>, sourced from human review and matured disputes by default.
        </p>
        <ValidationReportPanel />
      </section>
    </div>
  );
}
