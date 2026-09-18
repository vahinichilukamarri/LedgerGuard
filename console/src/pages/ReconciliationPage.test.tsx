import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReconciliationPage } from './ReconciliationPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

const RUN_ID = '55555555-5555-5555-5555-555555555555';
const INCIDENT_ID = '66666666-6666-6666-6666-666666666666';

function runsPage() {
  return {
    content: [
      {
        runId: RUN_ID,
        startedAt: '2026-09-10T08:00:00Z',
        completedAt: '2026-09-10T08:00:01Z',
        internalExamined: 4,
        externalExamined: 4,
        matched: 3,
        discrepancies: 1,
      },
    ],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  };
}

function incident(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: INCIDENT_ID,
    runId: RUN_ID,
    type: 'AMOUNT_MISMATCH',
    severity: 'HIGH',
    status: 'OPEN',
    transactionId: '77777777-7777-7777-7777-777777777777',
    settlementRecordId: null,
    internalAmountMinor: 1000,
    externalAmountMinor: 900,
    differenceMinor: 100,
    currency: 'USD',
    internalStatus: 'POSTED',
    externalStatus: 'SETTLED',
    detail: 'internal and external amounts disagree',
    createdAt: '2026-09-10T08:00:01Z',
    resolvedAt: null,
    ...overrides,
  };
}

function renderReconciliation() {
  server.use(
    http.get('/reconciliation/runs', () => HttpResponse.json(runsPage())),
    http.get('/reconciliation/incidents', () => HttpResponse.json([incident()])),
  );
  return renderAt(<ReconciliationPage />, '/reconciliation', '/reconciliation');
}

describe('ReconciliationPage', () => {
  it('shows run history and a real, backend-computed severity badge on incidents', async () => {
    renderReconciliation();

    // Waiting on the badge text, not 'AMOUNT_MISMATCH': that string is also a
    // static filter-dropdown option, present before the incidents query ever
    // resolves, so awaiting it would pass for the wrong reason.
    expect(await screen.findByText('High')).toBeInTheDocument();
    expect(screen.getAllByText('AMOUNT_MISMATCH').length).toBeGreaterThan(1); // the filter option, and the row
    expect(screen.getByText('3')).toBeInTheDocument(); // matched count in run history
  });

  it('resolves an incident only after the confirm dialog is accepted', async () => {
    server.use(
      http.post('/reconciliation/incidents/:id/resolve', () =>
        HttpResponse.json({ ...incident(), status: 'RESOLVED', resolvedAt: '2026-09-10T09:00:00Z' }),
      ),
    );
    renderReconciliation();
    await screen.findByText('High');

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /resolve…/i }));

    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent(/high amount_mismatch/i);

    await user.click(screen.getByRole('button', { name: /^resolve$/i }));
    await waitFor(() => expect(dialog).not.toBeInTheDocument());
  });
});
