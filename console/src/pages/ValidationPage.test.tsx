import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ValidationPage } from './ValidationPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

const ACCOUNT_ID = '88888888-8888-8888-8888-888888888888';

function candidate(blind: boolean) {
  return {
    accountId: ACCOUNT_ID,
    stratum: 'FLAGGED',
    blind,
    statisticalScore: blind ? null : 0.71,
    mlScore: blind ? null : 0.44,
    paymentsInBaseline: 12,
  };
}

function report() {
  return {
    asOf: '2026-09-10T08:00:00Z',
    warnings: [],
    quotable: true,
    labels: { total: 10, decisive: 9, unclear: 1, flaggedStratumLabels: 6, auditLabels: 4, disputeLabels: 2, syntheticLabels: 0, blindLabels: 5 },
    population: { flagged: 20, unflagged: 80, total: 100 },
    reviewerAgreement: { doublyReviewed: 0, agreed: 0, rate: null },
    statistical: {
      threshold: 0.5,
      counts: { truePositives: 3, falsePositives: 1, trueNegatives: 4, falseNegatives: 1, labelled: 9 },
      precision: { point: 0.75, low: 0.4, high: 0.9, trials: 4, informative: true },
      recall: { point: 0.6, low: 0.3, high: 0.8, trials: 5, informative: true },
      recallMeasurable: true,
      f1: 0.67,
      baseRate: 0.4,
      lift: 1.5,
      averagePrecision: 0.7,
      chanceLevel: 0.4,
      curve: [],
    },
    model: {
      threshold: 0.6,
      counts: { truePositives: 2, falsePositives: 2, trueNegatives: 3, falseNegatives: 2, labelled: 9 },
      precision: { point: 0.5, low: 0.2, high: 0.8, trials: 4, informative: true },
      recall: null,
      recallMeasurable: false,
      f1: null,
      baseRate: 0.4,
      lift: null,
      averagePrecision: null,
      chanceLevel: 0.4,
      curve: [],
    },
    agreementStates: {},
  };
}

function renderValidation() {
  server.use(
    http.get('/validation/review/next', ({ request }) => {
      const blind = new URL(request.url).searchParams.get('blind') === 'true';
      return HttpResponse.json(candidate(blind));
    }),
    http.get('/validation/review/census', () => HttpResponse.json({ flagged: 20, unflagged: 80 })),
    http.get('/validation/report', () => HttpResponse.json(report())),
  );
  return renderAt(<ValidationPage />, '/validation', '/validation');
}

describe('ValidationPage', () => {
  it('fetches the review queue blind by default, with no score in the response at all', async () => {
    renderValidation();

    await screen.findByText('Reveal scores');
    expect(screen.queryByText('0.71')).not.toBeInTheDocument();
    expect(screen.getByText(/recorded as unanchored/i)).toBeInTheDocument();
  });

  it('reveals scores only on explicit request, and then records the label as anchored', async () => {
    renderValidation();
    await screen.findByText('Reveal scores');

    const user = userEvent.setup();
    await user.click(screen.getByText('Reveal scores'));

    expect(await screen.findByText('0.71')).toBeInTheDocument();
    expect(screen.getByText(/recorded as anchored/i)).toBeInTheDocument();
  });

  it('computes the review census total client-side rather than trusting a wire field', async () => {
    renderValidation();
    expect(await screen.findByText('100')).toBeInTheDocument();
  });

  it('renders precision/recall for both layers, including an unmeasurable recall', async () => {
    renderValidation();
    expect(await screen.findByText('Statistical layer')).toBeInTheDocument();
    expect(screen.getByText('Model layer')).toBeInTheDocument();
    expect(screen.getByText(/unmeasurable — no audit stratum/i)).toBeInTheDocument();
  });
});
