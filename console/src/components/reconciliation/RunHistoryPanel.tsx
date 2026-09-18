import { useState } from 'react';
import { useReconciliationRuns, useRunReconciliation } from '../../api/queries';
import type { ReconciliationRunSummary } from '../../api/types';
import { instant } from '../../format';
import { describe, Failure, Pending } from '../States';
import { DataTable, type Column } from '../ledger/DataTable';

const PAGE_SIZE = 10;

/**
 * Triggering a run has no confirmation dialog: `ReconciliationService` says
 * why — it moves no money and writes no ledger rows, so running it twice
 * produces two reports of the same facts rather than doing anything twice.
 * That is a materially different risk than a refund, a reversal, or anything
 * under `/simulation`, and the UI weight matches it.
 */
export function RunHistoryPanel() {
  const [page, setPage] = useState(0);
  const query = useReconciliationRuns(page, PAGE_SIZE);
  const runMutation = useRunReconciliation();

  const columns: Column<ReconciliationRunSummary>[] = [
    { key: 'startedAt', header: 'Started', render: (run) => instant(run.startedAt) },
    { key: 'completedAt', header: 'Completed', render: (run) => instant(run.completedAt) },
    { key: 'internal', header: 'Internal examined', render: (run) => <span className="num">{run.internalExamined}</span> },
    { key: 'external', header: 'External examined', render: (run) => <span className="num">{run.externalExamined}</span> },
    { key: 'matched', header: 'Matched', render: (run) => <span className="num">{run.matched}</span> },
    { key: 'discrepancies', header: 'Discrepancies', render: (run) => <span className="num">{run.discrepancies}</span> },
  ];

  return (
    <section className="card">
      <h2>Run history</h2>
      <p className="card-note">
        <code>GET /reconciliation/runs</code>. A run compares the ledger against the simulated processor;
        every discrepancy it finds is filed as an incident below, unless the same one is already open from
        an earlier run.
      </p>

      <div className="form-actions" style={{ marginBottom: 18 }}>
        <button
          type="button"
          className="btn btn-primary"
          disabled={runMutation.isPending}
          onClick={() => runMutation.mutate()}
        >
          {runMutation.isPending ? 'Running…' : 'Run reconciliation now'}
        </button>
        {runMutation.isSuccess && (
          <span className="form-success">
            Examined {runMutation.data.internalExamined} internal / {runMutation.data.externalExamined}{' '}
            external, matched {runMutation.data.matched}, {runMutation.data.newIncidents} new incident
            {runMutation.data.newIncidents === 1 ? '' : 's'}.
          </span>
        )}
      </div>
      {runMutation.isError && <p className="form-error">{describe(runMutation.error)}</p>}

      {query.isPending && <Pending what="run history" />}
      {query.isError && <Failure what="run history" error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <DataTable
          columns={columns}
          rows={query.data.content}
          rowKey={(run) => run.runId}
          page={query.data.page}
          totalPages={query.data.totalPages}
          totalElements={query.data.totalElements}
          onPageChange={setPage}
          emptyMessage="No runs yet. Trigger one above."
        />
      )}
    </section>
  );
}
