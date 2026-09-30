import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import { OverviewPage } from './OverviewPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

function renderOverview() {
  server.use(
    http.get('/accounts', () =>
      HttpResponse.json({
        content: [{ id: '1', name: 'Payer', currency: 'USD', createdAt: '2026-09-10T08:00:00Z' }],
        page: 0,
        size: 5,
        totalElements: 1,
        totalPages: 1,
      }),
    ),
    http.get('/transactions', () =>
      HttpResponse.json({
        content: [{ id: '2', description: 'invoice 42', currency: 'USD', createdAt: '2026-09-10T08:00:00Z' }],
        page: 0,
        size: 5,
        totalElements: 1,
        totalPages: 1,
      }),
    ),
    http.get('/reconciliation/incidents', () =>
      HttpResponse.json([
        {
          id: 'i1',
          runId: 'r1',
          type: 'AMOUNT_MISMATCH',
          severity: 'HIGH',
          status: 'OPEN',
          transactionId: 't1',
          settlementRecordId: null,
          internalAmountMinor: 1000,
          externalAmountMinor: 900,
          differenceMinor: 100,
          currency: 'USD',
          internalStatus: 'POSTED',
          externalStatus: 'SETTLED',
          detail: 'mismatch',
          createdAt: '2026-09-10T08:00:00Z',
          resolvedAt: null,
        },
      ]),
    ),
    http.get('/reconciliation/runs', () =>
      HttpResponse.json({
        content: [
          {
            runId: 'r1',
            startedAt: '2026-09-10T08:00:00Z',
            completedAt: '2026-09-10T08:00:01Z',
            internalExamined: 4,
            externalExamined: 4,
            matched: 3,
            discrepancies: 1,
          },
        ],
        page: 0,
        size: 1,
        totalElements: 1,
        totalPages: 1,
      }),
    ),
    http.get('/detection/model', () => new HttpResponse(null, { status: 404 })),
    http.get('/detection/anomalies', () =>
      HttpResponse.json([
        { accountId: 'a1', asOf: '2026-09-10T08:00:00Z', explanation: { agreement: 'ML_ONLY' } },
        { accountId: 'a2', asOf: '2026-09-10T08:00:00Z', explanation: { agreement: 'ML_ONLY' } },
        { accountId: 'a3', asOf: '2026-09-10T08:00:00Z', explanation: { agreement: 'BOTH_ELEVATED' } },
      ]),
    ),
  );
  return renderAt(<OverviewPage />, '/overview', '/overview');
}

describe('OverviewPage', () => {
  it('composes five independent reads into a landing page, and says so', async () => {
    renderOverview();

    expect(await screen.findByText('Payer')).toBeInTheDocument();
    expect(screen.getByText('invoice 42')).toBeInTheDocument();
    expect(screen.getByText('No model trained')).toBeInTheDocument();
    expect(screen.getByText(/not a backend aggregate/i)).toBeInTheDocument();
  });

  it('breaks open incidents down by real severity, not a placeholder count, and colours the card by it', async () => {
    renderOverview();
    // The label renders on the first paint, before the incidents query
    // resolves — waiting on it would pass before the severity class is
    // even applied. Wait on the breakdown pill instead, which only exists
    // once the real data has loaded.
    await screen.findByText('high');
    const card = screen.getByText('Open reconciliation incidents').closest('.kpi-card')!;
    expect(card).toHaveClass('kpi-tone-severity-high');
    expect(card.querySelector('.kpi-value')).toHaveTextContent('1');
  });

  it('shows the latest reconciliation run with its real counts', async () => {
    renderOverview();
    expect(await screen.findByText(/3 matched, 1 discrepancy of 4 examined/)).toBeInTheDocument();
  });

  it('renders the detection agreement mix as a bar chart with real counts, colour-coded by category not severity', async () => {
    renderOverview();
    expect(await screen.findByText('Model only')).toBeInTheDocument();
    expect(screen.getByText('Both elevated')).toBeInTheDocument();
    // two ML_ONLY rows, one BOTH_ELEVATED — the bar counts must reflect that, not a placeholder.
    const modelOnlyRow = screen.getByText('Model only').closest('.bar-row');
    expect(modelOnlyRow).toHaveTextContent('2');
  });

  it('renders the open-incidents-by-severity chart with the real severity tone, and the run-history chart with real matched/examined counts', async () => {
    renderOverview();

    const severityRow = (await screen.findByText('High')).closest('.bar-row')!;
    expect(severityRow.querySelector('.bar-fill')).toHaveClass('tone-severity-high');
    expect(severityRow).toHaveTextContent('1');

    const runsChart = (await screen.findByText('Recent runs — matched vs. examined')).closest(
      '.chart-container',
    ) as HTMLElement;
    expect(within(runsChart).getByText('3/4')).toBeInTheDocument();
  });
});
