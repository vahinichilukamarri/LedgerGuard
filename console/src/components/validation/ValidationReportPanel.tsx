import { useValidationReport } from '../../api/queries';
import type { ValidationLayer } from '../../api/types';
import { instant, score } from '../uncertainty/format';
import { Failure, Pending } from '../States';

function fmt(value: number | null | undefined, digits = 2): string {
  return value === null || value === undefined ? '—' : value.toFixed(digits);
}

function LayerCard({ title, layer }: { title: string; layer: ValidationLayer }) {
  return (
    <div className="stat-block">
      <h3>{title}</h3>
      <dl className="facts">
        <dt>Threshold</dt>
        <dd>{score(layer.threshold)}</dd>
        <dt>Labelled</dt>
        <dd>{layer.counts.labelled}</dd>
        <dt>Precision</dt>
        <dd>
          {fmt(layer.precision.point)}
          {!layer.precision.informative && ' (not informative — too few trials)'}
        </dd>
        <dt>Recall</dt>
        <dd>{layer.recallMeasurable ? fmt(layer.recall?.point ?? null) : 'unmeasurable — no audit stratum'}</dd>
        <dt>F1</dt>
        <dd>{fmt(layer.f1)}</dd>
        <dt>Base rate</dt>
        <dd>{fmt(layer.baseRate)}</dd>
        <dt>Lift</dt>
        <dd>{fmt(layer.lift)}</dd>
      </dl>
    </div>
  );
}

/**
 * A summary, not the full payload: `curve` (the whole precision/recall
 * trade-off) is in the response but not rendered — there is no charting
 * library in this project yet, and a table of forty threshold points would
 * not read as one. The numbers here are exactly what the backend published,
 * not a derived blend of them.
 */
export function ValidationReportPanel() {
  const query = useValidationReport();

  if (query.isPending) {
    return <Pending what="the validation report" />;
  }
  if (query.isError) {
    return <Failure what="the validation report" error={query.error} onRetry={() => void query.refetch()} />;
  }

  const report = query.data;

  return (
    <div>
      {!report.quotable && (
        <p className="form-error">
          Not quotable: the backend does not consider this report's numbers safe to cite as-is.
        </p>
      )}
      {report.warnings.length > 0 && (
        <ul className="th-note" style={{ marginBottom: 16 }}>
          {report.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}

      <dl className="facts" style={{ marginBottom: 18 }}>
        <dt>As of</dt>
        <dd>{instant(report.asOf)}</dd>
        <dt>Labels — total / decisive / unclear</dt>
        <dd>
          {report.labels.total} / {report.labels.decisive} / {report.labels.unclear}
        </dd>
        <dt>Labels — flagged / audit</dt>
        <dd>
          {report.labels.flaggedStratumLabels} / {report.labels.auditLabels}
        </dd>
        <dt>Labels — dispute / synthetic / blind</dt>
        <dd>
          {report.labels.disputeLabels} / {report.labels.syntheticLabels} / {report.labels.blindLabels}
        </dd>
        <dt>Population — flagged / unflagged / total</dt>
        <dd>
          {report.population.flagged} / {report.population.unflagged} / {report.population.total}
        </dd>
        <dt>Reviewer agreement</dt>
        <dd>
          {report.reviewerAgreement.rate === null
            ? 'not measured — nobody re-reviewed'
            : `${fmt(report.reviewerAgreement.rate)} (${report.reviewerAgreement.agreed}/${report.reviewerAgreement.doublyReviewed})`}
        </dd>
      </dl>

      <div className="grid-two">
        <LayerCard title="Statistical layer" layer={report.statistical} />
        <LayerCard title="Model layer" layer={report.model} />
      </div>
    </div>
  );
}
