import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
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
  it('composes independent reads into one dashboard, and says so', async () => {
    renderOverview();

    expect(await screen.findByText('Payer')).toBeInTheDocument();
    expect(screen.getByText('invoice 42')).toBeInTheDocument();
    expect(screen.getByText(/read live from its own endpoint/i)).toBeInTheDocument();
  });

  it('shows real totals in the stat cards, not placeholders', async () => {
    renderOverview();

    const accounts = (await screen.findByText('Accounts', { selector: '.stat-card-label' })).closest('.stat-card')!;
    const transactions = screen.getByText('Transactions posted').closest('.stat-card')!;
    await waitFor(() => {
      expect(accounts.querySelector('.stat-card-value')).toHaveTextContent('1');
      expect(transactions.querySelector('.stat-card-value')).toHaveTextContent('1');
    });
  });

  it('breaks open incidents down by real severity and tones the card by the worst one', async () => {
    renderOverview();
    // The label renders on the first paint, before the incidents query resolves —
    // wait on the breakdown, which only exists once the real data has loaded.
    await screen.findByText('high', { selector: '.stat-breakdown span' });
    const card = screen.getByText('Open reconciliation incidents').closest('.stat-card')!;
    expect(card).toHaveClass('stat-severity-high');
    expect(card.querySelector('.stat-card-value')).toHaveTextContent('1');
  });

  it('shows the latest reconciliation run with its real counts', async () => {
    renderOverview();
    expect(await screen.findByText(/3 matched, 1 discrepancy of 4 examined/)).toBeInTheDocument();
  });

  it('draws the detection agreement mix from real counts, labelled by category rather than severity', async () => {
    renderOverview();
    const chart = (await screen.findByText('Detection agreement')).closest('.chart-card') as HTMLElement;

    // two ML_ONLY rows, one BOTH_ELEVATED — the counts must reflect that, not a placeholder.
    const modelOnly = await within(chart).findByText('Model only');
    expect(modelOnly.closest('.legend-row')).toHaveTextContent('2');
    expect(within(chart).getByText('Both elevated').closest('.legend-row')).toHaveTextContent('1');
  });

  it('draws open incidents by severity with the real severity colour', async () => {
    renderOverview();
    const chart = (await screen.findByText('Open incidents by severity')).closest('.chart-card') as HTMLElement;

    const high = await within(chart).findByText('High');
    expect(high.closest('.legend-row')).toHaveTextContent('1');
    expect(within(chart).getByText('High').closest('.legend-row')!.querySelector('.legend-swatch')).toHaveStyle({
      background: 'var(--severity-high)',
    });
  });

  it('plots the run history from the runs endpoint', async () => {
    renderOverview();
    const chart = (await screen.findByText('Reconciliation runs')).closest('.chart-card') as HTMLElement;
    expect(await within(chart).findByRole('img')).toHaveAttribute('aria-label', expect.stringContaining('1 columns'));
    expect(within(chart).getByLabelText(/Matched 3, Discrepancies 1/)).toBeInTheDocument();
  });

  it('says plainly when there is no model to plot a second score from', async () => {
    renderOverview();
    expect(await screen.findByText('No model trained', { selector: '.empty-state-title' })).toBeInTheDocument();
  });
});
