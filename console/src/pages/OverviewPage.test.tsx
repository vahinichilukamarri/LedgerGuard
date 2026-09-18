import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
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
    http.get('/reconciliation/incidents', () => HttpResponse.json([])),
    http.get('/detection/model', () => new HttpResponse(null, { status: 404 })),
    http.get('/detection/anomalies', () => HttpResponse.json([])),
    http.get('/validation/review/census', () => HttpResponse.json({ flagged: 0, unflagged: 0 })),
  );
  return renderAt(<OverviewPage />, '/overview', '/overview');
}

describe('OverviewPage', () => {
  it('composes six independent reads into a landing page, and says so', async () => {
    renderOverview();

    expect(await screen.findByText('Payer')).toBeInTheDocument();
    expect(screen.getByText('invoice 42')).toBeInTheDocument();
    expect(screen.getByText('No model trained')).toBeInTheDocument();
    expect(screen.getByText(/not a backend aggregate/i)).toBeInTheDocument();
  });
});
