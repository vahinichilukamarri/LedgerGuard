import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { ACCOUNTS, assessment, dilutedExplanation, explanation, label } from './fixtures';
import type { AccountAssessment, AccountExplanation, AccountLabel } from '../api/types';

/**
 * A mock backend, not a pile of fetch stubs.
 *
 * <h2>Flakiness is the thing this file exists to prevent</h2>
 *
 * Phase 7 and Phase 11 both spent their budget on tests that passed for reasons
 * unrelated to what they claimed. The frontend equivalent is a test that races
 * its own data fetch: a component that renders empty, then renders again, and an
 * assertion that happens to run between the two.
 *
 * Four rules, applied everywhere:
 *
 * <ol>
 *   <li>Handlers resolve <b>synchronously</b>. No artificial latency, no
 *       randomised delay — an async boundary in a fixture buys nothing and costs
 *       determinism. Where a test needs a slow request it asks for one
 *       explicitly, with a promise it resolves itself.</li>
 *   <li>Unhandled requests <b>fail the test</b>. A component that starts
 *       fetching something nobody declared is a bug, and the default of quietly
 *       passing the request through hides it.</li>
 *   <li>Every query client in tests has <b>retry off</b>. A retrying query turns
 *       one deterministic failure into a timing-dependent sequence of them.</li>
 *   <li>Assertions on fetched content use <code>findBy</code>, never
 *       <code>getBy</code> after a bare <code>await</code>. The query is what
 *       waits, so there is no arbitrary tick to get wrong.</li>
 * </ol>
 */

const EXPLANATIONS: Record<string, AccountExplanation> = {
  [ACCOUNTS.bothElevated]: explanation(ACCOUNTS.bothElevated, 'BOTH_ELEVATED'),
  [ACCOUNTS.bothElevatedUnrelated]: {
    ...explanation(ACCOUNTS.bothElevatedUnrelated, 'BOTH_ELEVATED'),
    reconciliation: {
      ...explanation(ACCOUNTS.bothElevatedUnrelated, 'BOTH_ELEVATED').reconciliation!,
      corroborated: false,
      corroboratedSignals: [],
    },
  },
  [ACCOUNTS.statisticalOnly]: explanation(ACCOUNTS.statisticalOnly, 'STATISTICAL_ONLY'),
  [ACCOUNTS.mlOnly]: {
    ...explanation(ACCOUNTS.mlOnly, 'ML_ONLY'),
    reconciliation: {
      ...explanation(ACCOUNTS.mlOnly, 'ML_ONLY').reconciliation!,
      corroborated: false,
      corroboratedSignals: [],
    },
  },
  [ACCOUNTS.mlOnlyDiluted]: dilutedExplanation(ACCOUNTS.mlOnlyDiluted),
  [ACCOUNTS.bothQuiet]: explanation(ACCOUNTS.bothQuiet, 'BOTH_QUIET'),
  [ACCOUNTS.noModel]: explanation(ACCOUNTS.noModel, null),
};

const RANKING: AccountAssessment[] = [
  assessment(ACCOUNTS.bothElevated, 'BOTH_ELEVATED'),
  { ...assessment(ACCOUNTS.bothElevatedUnrelated, 'BOTH_ELEVATED'), explanation: { ...assessment(ACCOUNTS.bothElevatedUnrelated, 'BOTH_ELEVATED').explanation, corroborated: false } },
  assessment(ACCOUNTS.statisticalOnly, 'STATISTICAL_ONLY'),
  { ...assessment(ACCOUNTS.mlOnly, 'ML_ONLY'), explanation: { ...assessment(ACCOUNTS.mlOnly, 'ML_ONLY').explanation, corroborated: false } },
  { ...assessment(ACCOUNTS.mlOnlyDiluted, 'ML_ONLY'), explanation: { ...assessment(ACCOUNTS.mlOnlyDiluted, 'ML_ONLY').explanation, corroborated: true } },
];

const LABELS: Record<string, AccountLabel[]> = {
  [ACCOUNTS.bothElevated]: [label({ accountId: ACCOUNTS.bothElevated })],
  [ACCOUNTS.statisticalOnly]: [
    label({
      id: '22222222-2222-2222-2222-222222222222',
      accountId: ACCOUNTS.statisticalOnly,
      verdict: 'ANOMALOUS',
      source: 'SYNTHETIC',
      stratum: 'FLAGGED',
      reviewer: 'generator',
      scoresVisible: true,
    }),
  ],
  [ACCOUNTS.mlOnly]: [
    label({
      id: '33333333-3333-3333-3333-333333333333',
      accountId: ACCOUNTS.mlOnly,
      verdict: 'UNCLEAR',
      source: 'HUMAN_REVIEW',
      stratum: 'AUDIT',
      reviewer: 'alex',
      scoresVisible: false,
      latencySeconds: 3600,
    }),
  ],
};

export const handlers = [
  http.get('/detection/anomalies', () => HttpResponse.json(RANKING)),

  http.get('/detection/accounts/:accountId/explanation', ({ params, request }) => {
    const accountId = String(params['accountId']);
    const found = EXPLANATIONS[accountId];
    if (!found) {
      return new HttpResponse(null, { status: 404 });
    }
    // The one behaviour of the real endpoint that matters here: ?narrative=template
    // never returns LLM prose, and the default may.
    const preferTemplate = new URL(request.url).searchParams.get('narrative') === 'template';
    return HttpResponse.json(preferTemplate ? { ...found, narrativeSource: 'TEMPLATE' } : found);
  }),

  http.get('/validation/labels/:accountId', ({ params }) =>
    HttpResponse.json(LABELS[String(params['accountId'])] ?? []),
  ),

  http.get('/detection/model', () =>
    HttpResponse.json({
      seed: 20240917,
      trees: 128,
      subSampleSize: 256,
      trainingAccounts: 512,
      trainedAt: '2026-09-10T08:00:00Z',
      trainedAsOf: '2026-09-10T07:59:00Z',
    }),
  ),
];

export const server = setupServer(...handlers);

export { ACCOUNTS, EXPLANATIONS, RANKING, LABELS };
export { http, HttpResponse };
