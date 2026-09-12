import type {
  AccountAssessment,
  AccountExplanation,
  AccountLabel,
  AgreementState,
  FeatureAttribution,
  ModelInfo,
  NarrativeSource,
  SignalContribution,
} from '../api/types';

/**
 * Fixtures shaped like the real responses, built from the Java records.
 *
 * Every number here is internally consistent with the identities the backend
 * publishes — signal shares sum to one, attribution shares sum to one across the
 * isolating features — because a fixture that violates them would let a
 * component pass a test it would fail against the real API.
 */

export const MODEL: ModelInfo = {
  seed: 20240917,
  trees: 128,
  subSampleSize: 256,
  trainingAccounts: 512,
  trainedAt: '2026-09-10T08:00:00Z',
  trainedAsOf: '2026-09-10T07:59:00Z',
};

export function signal(
  name: string,
  overrides: Partial<SignalContribution> = {},
): SignalContribution {
  return {
    signal: name,
    applicable: true,
    fired: false,
    statistic: 1.2,
    score: 0,
    weight: 0.2,
    contribution: 0,
    explanation: `${name} looked at this account and had something to say.`,
    subjectId: null,
    ...overrides,
  };
}

/** Five signals whose shares sum to one, with the named signal carrying most of it. */
export function signalsDrivenBy(name: string): SignalContribution[] {
  const others = ['amount_outlier', 'velocity', 'burst', 'reconciliation_mismatch_rate', 'refund_reversal_rate']
    .filter((candidate) => candidate !== name);

  return [
    signal(name, { fired: true, statistic: 7.4, score: 0.82, contribution: 0.6, weight: 0.25 }),
    signal(others[0] ?? 'velocity', { fired: true, statistic: 4.1, score: 0.34, contribution: 0.25 }),
    signal(others[1] ?? 'burst', { fired: true, statistic: 3.4, score: 0.11, contribution: 0.15 }),
    signal(others[2] ?? 'reconciliation_mismatch_rate', { statistic: 0.4, score: 0 }),
    signal(others[3] ?? 'refund_reversal_rate', {
      applicable: false,
      statistic: null,
      explanation: 'Too little history to judge.',
    }),
  ];
}

export function attribution(
  feature: string,
  overrides: Partial<FeatureAttribution> = {},
): FeatureAttribution {
  return {
    feature,
    index: 0,
    value: 4.2,
    splitsPerTree: 1.4,
    isolationBits: 2.1,
    excessBits: 0,
    share: 0,
    percentile: 0.5,
    median: 4.2,
    ...overrides,
  };
}

export const ATTRIBUTIONS: FeatureAttribution[] = [
  attribution('amountModifiedZ', { index: 0, value: 8.1, excessBits: 1.4, share: 0.62, percentile: 0.99, median: 1.1 }),
  attribution('accountAgeDays', { index: 6, value: 3, excessBits: 0.6, share: 0.38, percentile: 0.02, median: 210 }),
  attribution('velocitySurprisal', { index: 1, value: 0.4, excessBits: 0, share: 0, percentile: 0.44, median: 0.5 }),
  attribution('dataCompleteness', { index: 10, value: 0.6, excessBits: 0, share: 0, percentile: 0.31, median: 0.9 }),
];

// ---------------------------------------------------------------- list rows

export function assessment(
  accountId: string,
  agreement: AgreementState | null,
  overrides: Partial<AccountAssessment> = {},
): AccountAssessment {
  const statisticalScore =
    agreement === 'BOTH_ELEVATED' || agreement === 'STATISTICAL_ONLY' ? 0.71 : 0.22;
  const mlScore = agreement === 'BOTH_ELEVATED' || agreement === 'ML_ONLY' ? 0.78 : 0.41;

  return {
    accountId,
    asOf: '2026-09-12T10:00:00Z',
    statisticalScore,
    applicableSignals: 4,
    wellEvidenced: true,
    ml:
      agreement === null
        ? {
            available: false,
            score: null,
            agreement: null,
            unavailableReason: 'no model has been trained; POST /detection/model/train to train one',
            model: null,
          }
        : { available: true, score: mlScore, agreement, unavailableReason: null, model: MODEL },
    signals: signalsDrivenBy('amount_outlier'),
    explanation: {
      summary:
        'The statistical layer scored this account 0.71 over four applicable signals. Neither ' +
        'score is validated against ground truth.',
      agreement,
      corroborated: agreement === 'BOTH_ELEVATED',
      statisticalDrivers: ['amount_outlier', 'velocity'],
      modelDrivers: agreement === null ? [] : ['amountModifiedZ', 'accountAgeDays'],
      modelDriversOutsideStatisticalView: agreement === null ? [] : ['accountAgeDays'],
      detail: `/detection/accounts/${accountId}/explanation`,
    },
    ...overrides,
  };
}

// ------------------------------------------------------------------ detail

export function explanation(
  accountId: string,
  agreement: AgreementState | null,
  narrativeSource: NarrativeSource = 'TEMPLATE',
  overrides: Partial<AccountExplanation> = {},
): AccountExplanation {
  const row = assessment(accountId, agreement);
  const corroborated = agreement === 'BOTH_ELEVATED';

  return {
    accountId,
    asOf: row.asOf,
    statistical: {
      composite: row.statisticalScore,
      applicableSignals: 4,
      wellEvidenced: true,
      applicableWeight: 0.85,
      contributions: signalsDrivenBy('amount_outlier'),
    },
    ml:
      agreement === null
        ? null
        : {
            score: row.ml.score ?? 0,
            expectedPathLength: 7.42,
            trainingRows: 512,
            model: MODEL,
            attributions: ATTRIBUTIONS,
          },
    mlUnavailableReason: agreement === null ? row.ml.unavailableReason : null,
    reconciliation:
      agreement === null
        ? null
        : {
            agreement,
            statisticalDrivers: ['amount_outlier', 'velocity'],
            modelDrivers: ['amountModifiedZ', 'accountAgeDays'],
            corroboratedSignals: corroborated ? ['amount_outlier'] : [],
            modelDriversOutsideView: ['accountAgeDays'],
            corroborated,
            narrative:
              'The two layers are described here in the API’s own words, which differ per state.',
          },
    summary:
      narrativeSource === 'LLM'
        ? 'This account’s largest recent payment sits well outside its own established range, and ' +
          'the model isolated it chiefly on that same measurement. Neither score is validated.'
        : 'The statistical layer scored this account over four applicable signals. Neither score ' +
          'is validated against ground truth.',
    narrativeSource,
    caveats: [
      'Neither score is validated: there is no labelled data in this system, so precision and ' +
        'recall are unmeasured rather than approximately known.',
      'The statistical weights are unfitted judgement, not learned parameters.',
    ],
    ...overrides,
  };
}

/**
 * The dilution case: the model is elevated, a shared-axis signal fired, and the
 * composite still stayed under the line. Not a disagreement between layers.
 */
export function dilutedExplanation(accountId: string): AccountExplanation {
  const base = explanation(accountId, 'ML_ONLY');
  return {
    ...base,
    reconciliation: {
      ...base.reconciliation!,
      corroboratedSignals: ['amount_outlier'],
      corroborated: true,
    },
  };
}

// ------------------------------------------------------------------ labels

export function label(overrides: Partial<AccountLabel> = {}): AccountLabel {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    accountId: 'aaaaaaaa-0000-0000-0000-000000000001',
    verdict: 'ANOMALOUS',
    source: 'DISPUTE_FEED',
    stratum: 'FLAGGED',
    reviewer: 'scheme:visa',
    scoresVisible: false,
    labelledAsOf: '2026-08-01T00:00:00Z',
    observedAt: '2026-09-01T00:00:00Z',
    latencySeconds: 2678400,
    evidenceId: null,
    notes: null,
    ...overrides,
  };
}

export const ACCOUNTS = {
  bothElevated: 'aaaaaaaa-0000-0000-0000-000000000001',
  bothElevatedUnrelated: 'aaaaaaaa-0000-0000-0000-000000000002',
  statisticalOnly: 'aaaaaaaa-0000-0000-0000-000000000003',
  mlOnly: 'aaaaaaaa-0000-0000-0000-000000000004',
  mlOnlyDiluted: 'aaaaaaaa-0000-0000-0000-000000000005',
  bothQuiet: 'aaaaaaaa-0000-0000-0000-000000000006',
  noModel: 'aaaaaaaa-0000-0000-0000-000000000007',
} as const;
