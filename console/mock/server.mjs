/**
 * A stand-in for the Spring application, for looking at the console.
 *
 * <h2>Why this exists</h2>
 *
 * Reviewing whether a screen overstates what the system knows means looking at
 * the screen. Doing that against the real backend means Postgres, a seeded
 * ledger, a trained forest and a Groq key — which is right for judging the
 * detector and disproportionate for judging a layout.
 *
 * So this serves fixed responses in the real wire shapes, covering every state
 * the console has a distinct treatment for, including two the real system
 * produces only occasionally: a model-written narrative, and a narrative slow
 * enough to see the pending state.
 *
 * <h2>Self-consistent by construction, not by hand</h2>
 *
 * The first version of this file hand-wrote each account's narrative and its
 * driver lists separately, and they disagreed: every row named the same three
 * model drivers regardless of what its own attribution said, and three of the
 * feature names did not exist in the real system at all. A mock whose parts
 * contradict each other is worse than no mock, because a screenshot taken from
 * it looks authoritative.
 *
 * So the eleven features below are the real eleven, in the real order, with the
 * real visibility from `FeatureProvenance`; shares are computed from excess
 * bits rather than typed; and the driver lists, the corroboration, the
 * outside-view list and the narrative are all derived by the same rules
 * `Reconciliation.of` uses. Only the raw evidence per account is hand-written.
 *
 * <h2>What it is not</h2>
 *
 * Not a test double. The tests use their own fixtures and MSW, and they are what
 * pins behaviour; nothing here is imported by them, and a change here cannot
 * make a test pass.
 *
 * Usage (PowerShell):
 *   npm run mock                  # serves on http://localhost:8080
 *   npm run dev                   # Vite proxies /detection and /validation here
 */

import { createServer } from 'node:http';

const PORT = Number(process.env['MOCK_PORT'] ?? 8080);

/** How long the default narrative takes, so the pending state is visible. */
const NARRATIVE_DELAY_MS = Number(process.env['MOCK_NARRATIVE_DELAY_MS'] ?? 1500);

/** Agreement.STATISTICAL_ELEVATED and Agreement.ML_ELEVATED. */
const STATISTICAL_ELEVATED = 0.5;
const ML_ELEVATED = 0.6;

const MODEL = {
  seed: 20240917,
  trees: 128,
  subSampleSize: 256,
  trainingAccounts: 512,
  trainedAt: '2026-09-12T09:14:02Z',
  trainedAsOf: '2026-09-12T09:12:00Z',
};

const TRAINING_ROWS = 512;
const AS_OF = '2026-09-12T10:30:00Z';

// ------------------------------------------------------------ the features

/**
 * The eleven features, in index order, with the visibility `FeatureProvenance`
 * gives each. `signal` is the statistical signal that shares the axis, where one
 * does; OUTSIDE features have none by definition.
 */
const FEATURES = [
  { name: 'amountModifiedZ', visibility: 'SIGNAL_STATISTIC', signal: 'amount_outlier', median: 0.83 },
  { name: 'velocitySurprisal', visibility: 'SIGNAL_STATISTIC', signal: 'velocity', median: 0.41 },
  { name: 'burstSurprisal', visibility: 'SIGNAL_STATISTIC', signal: 'burst', median: 0.38 },
  { name: 'mismatchSurprisal', visibility: 'SIGNAL_STATISTIC', signal: 'reconciliation_mismatch_rate', median: 0.44 },
  { name: 'returnSurprisal', visibility: 'SIGNAL_STATISTIC', signal: 'refund_reversal_rate', median: 0.51 },
  { name: 'log10LargestRecentAmount', visibility: 'OUTSIDE', signal: null, median: 1.78 },
  { name: 'recentPaymentCount', visibility: 'SAME_AXIS', signal: 'velocity', median: 7 },
  { name: 'log10SecondsSinceLastPayment', visibility: 'OUTSIDE', signal: null, median: 4.97 },
  { name: 'historicalAmountPercentile', visibility: 'SAME_AXIS', signal: 'amount_outlier', median: 0.5 },
  { name: 'returnedPaymentFraction', visibility: 'SAME_AXIS', signal: 'refund_reversal_rate', median: 0.0 },
  { name: 'dataCompleteness', visibility: 'SAME_AXIS', signal: null, median: 1.0 },
];

const FEATURE_INDEX = new Map(FEATURES.map((feature, index) => [feature.name, index]));

/**
 * All eleven attributions for one account.
 *
 * `excess` names only the features that isolated it; everything else gets zero
 * excess bits and a zero share, which is what the real endpoint publishes for a
 * feature that pushed an account toward the crowd. Shares are excess bits over
 * total positive excess bits, so they sum to one across the isolating features
 * exactly as `MlExplanation.of` computes them.
 */
function attributionsFor({ values, percentiles, excess }) {
  const totalExcess = Object.values(excess).reduce((sum, bits) => sum + bits, 0);

  return FEATURES.map((feature, index) => {
    const excessBits = excess[feature.name] ?? 0;
    const isolationBits = 1.2 + excessBits + index * 0.04;

    return {
      feature: feature.name,
      index,
      value: values[feature.name] ?? feature.median,
      splitsPerTree: Number((2.4 - index * 0.12 + excessBits * 0.3).toFixed(2)),
      isolationBits: Number(isolationBits.toFixed(3)),
      excessBits: Number(excessBits.toFixed(3)),
      share: totalExcess > 0 && excessBits > 0 ? Number((excessBits / totalExcess).toFixed(3)) : 0,
      percentile: percentiles[feature.name] ?? 0.5,
      median: feature.median,
    };
  }).sort((left, right) => right.excessBits - left.excessBits || left.feature.localeCompare(right.feature));
}

// -------------------------------------------------------------- the signals

function signal(name, weight, { applicable = true, fired = false, statistic = null, score = 0, contribution = 0, explanation, subjectId = null }) {
  return { signal: name, applicable, fired, statistic, score, weight, contribution, explanation, subjectId };
}

const WEIGHTS = {
  amount_outlier: 0.25,
  velocity: 0.2,
  burst: 0.2,
  reconciliation_mismatch_rate: 0.2,
  refund_reversal_rate: 0.15,
};

/** Ordered by contribution, as `StatisticalExplanation.of` orders them. */
function contributionsOf(entries) {
  return [...entries].sort(
    (left, right) => right.contribution - left.contribution || left.signal.localeCompare(right.signal),
  );
}

// ----------------------------------------------------- derived, not typed

const NAMED_DRIVERS = 3;

function topDrivers(attributions) {
  return attributions.filter((entry) => entry.excessBits > 0).slice(0, NAMED_DRIVERS);
}

/**
 * `Reconciliation.of`, reimplemented over the mock's own data.
 *
 * Reimplemented rather than hand-written per account, so the driver lists, the
 * corroboration and the narrative cannot disagree with the attribution table the
 * same response carries.
 */
function reconcile(account, attributions) {
  const agreement = agreementOf(account.composite, account.mlScore);
  const fired = contributionsOf(account.contributions).filter((entry) => entry.contribution > 0);
  const firedNames = new Set(fired.map((entry) => entry.signal));

  const isolating = topDrivers(attributions);
  const modelDrivers = isolating.map((entry) => entry.feature);

  const corroboratedSignals = [];
  const outsideView = [];
  for (const driver of isolating) {
    const feature = FEATURES[FEATURE_INDEX.get(driver.feature)];
    if (feature.visibility === 'OUTSIDE') {
      outsideView.push(driver.feature);
      continue;
    }
    if (feature.signal && firedNames.has(feature.signal) && !corroboratedSignals.includes(feature.signal)) {
      corroboratedSignals.push(feature.signal);
    }
  }

  const corroborated = corroboratedSignals.length > 0;

  return {
    agreement,
    statisticalDrivers: fired.slice(0, NAMED_DRIVERS).map((entry) => entry.signal),
    modelDrivers,
    corroboratedSignals,
    modelDriversOutsideView: outsideView,
    corroborated,
    narrative: narrate(account, agreement, fired, isolating, corroboratedSignals, corroborated, outsideView),
  };
}

function agreementOf(composite, mlScore) {
  const statistical = composite >= STATISTICAL_ELEVATED;
  const ml = mlScore >= ML_ELEVATED;
  if (statistical && ml) return 'BOTH_ELEVATED';
  if (statistical) return 'STATISTICAL_ONLY';
  if (ml) return 'ML_ONLY';
  return 'BOTH_QUIET';
}

function join(parts) {
  if (parts.length === 0) return 'nothing';
  if (parts.length === 1) return parts[0];
  return `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`;
}

function ordinal(percentile) {
  const rank = Math.round(percentile * 100);
  const mod100 = rank % 100;
  const suffix =
    mod100 >= 11 && mod100 <= 13 ? 'th' : rank % 10 === 1 ? 'st' : rank % 10 === 2 ? 'nd' : rank % 10 === 3 ? 'rd' : 'th';
  return `${rank}${suffix}`;
}

function describeDrivers(isolating) {
  if (isolating.length === 0) {
    return 'no feature in particular — nothing isolated it faster than an even split would have';
  }
  return join(
    isolating.map(
      (driver) =>
        `${driver.feature} (${Math.round(driver.share * 100)}% of the isolation, ` +
        `${driver.value >= driver.median ? 'above' : 'below'} the population median at the ` +
        `${ordinal(driver.percentile)} percentile)`,
    ),
  );
}

function narrate(account, agreement, fired, isolating, corroboratedSignals, corroborated, outsideView) {
  const signals = join(fired.slice(0, NAMED_DRIVERS).map((entry) => entry.signal));
  const features = describeDrivers(isolating);
  const capitalise = (text) => (text ? text.charAt(0).toUpperCase() + text.slice(1) : text);

  switch (agreement) {
    case 'BOTH_ELEVATED': {
      if (!corroborated) {
        return (
          `Both layers are elevated, but not on the same evidence. The composite rests on ${signals}, ` +
          `while the model isolated this account on ${features}. Agreeing that something is unusual ` +
          'is not the same as agreeing on what, and nothing here corroborates anything.'
        );
      }
      const alsoInvisible =
        outsideView.length > 0
          ? ` The model also isolated on ${join(outsideView)}, which no statistical signal sees, so the ` +
            'corroboration covers part of its reasoning rather than all of it.'
          : '';
      return (
        `Both layers are elevated and they point at the same behaviour: ${signals} fired ` +
        `statistically, and the model isolated this account chiefly on ${features}. The shared axis ` +
        `is ${join(corroboratedSignals)}.${alsoInvisible}`
      );
    }

    case 'STATISTICAL_ONLY':
      return (
        `The statistical layer is elevated and the model is not. ${capitalise(signals)} fired against ` +
        `this account’s own history, but at ${account.mlScore.toFixed(2)} the isolation score is ` +
        `below the ${ML_ELEVATED.toFixed(2)} convention: across the ${TRAINING_ROWS} accounts the ` +
        'model was trained on, behaviour like this is not rare enough to isolate. Unusual for this ' +
        'account and ordinary for the population is the ordinary reading; the other reading is that ' +
        'the model’s features do not capture what the signals caught.'
      );

    case 'ML_ONLY': {
      const outsideNote =
        outsideView.length > 0
          ? `The statistical layer does not see ${join(outsideView)} at all, so there is nothing in ` +
            'the composite to corroborate or contradict this.'
          : 'Every axis the model isolated on is one some statistical signal also looks at, so the two ' +
            'layers are disagreeing about the same evidence rather than seeing different evidence.';

      let dilutionNote = '';
      if (corroborated) {
        const diluted = fired.filter((entry) => corroboratedSignals.includes(entry.signal));
        if (diluted.length > 0) {
          const named = diluted
            .map(
              (entry) =>
                `${entry.signal} at ${entry.score.toFixed(2)} of its own scale, supplying ` +
                `${Math.round(entry.contribution * 100)}% of the score`,
            )
            .join(' and ');
          dilutionNote =
            ` Note that ${join(corroboratedSignals)} did fire — ${named} — while the composite over ` +
            `${account.applicableSignals} applicable signals stayed below the ` +
            `${STATISTICAL_ELEVATED.toFixed(2)} convention. On that axis the layers are not ` +
            'disagreeing; the statistical score simply did not carry it over the line.';
        }
      }

      return (
        `The model is elevated and the statistical layer is not. It isolated this account on ` +
        `${features}, while the composite of ${account.composite.toFixed(2)} rests on ` +
        `${fired.length === 0 ? 'no signal at all' : signals}. ${outsideNote}${dilutionNote}`
      );
    }

    case 'BOTH_QUIET':
      return (
        `Neither layer is elevated: a composite of ${account.composite.toFixed(2)} over ` +
        `${account.applicableSignals} applicable signal(s) and an isolation score of ` +
        `${account.mlScore.toFixed(2)}, where roughly 0.5 is the middle of the distribution rather ` +
        'than a threshold. Quiet in both layers is the weakest claim either can make, not a clean ' +
        'bill of health.'
      );
  }
}

/** `SummaryWriter.caveats`, for the two conditions the fixtures actually hit. */
function caveatsFor(account) {
  const caveats = [
    'Neither score is validated: there is no labelled data in this system, so precision and recall ' +
      'are unmeasured rather than approximately known.',
    'The statistical weights are unfitted judgement, not learned parameters.',
  ];

  if (!account.wellEvidenced) {
    caveats.push(
      `The composite rests on ${account.applicableSignals} applicable signal(s), so most of the ` +
        'evidence this system can gather was never available for this account.',
    );
  }

  const unmeasurable = account.contributions.filter((entry) => !entry.applicable).length;
  if (unmeasurable > 0) {
    caveats.push(
      `${unmeasurable} of five signals had too little history to judge. That is an absence of ` +
        'evidence, not evidence that those behaviours were absent.',
    );
  }

  caveats.push(
    'Feature attribution is reconstructed from this account’s paths through the forest under one ' +
      'credit rule. It is not a unique decomposition of the score, and correlated features can take ' +
      'credit a single one of them would have earned alone.',
    `Percentiles are context, not the model’s reasoning: they say where a value sits among the ` +
      `${TRAINING_ROWS} accounts in the training snapshot, which is a different question from what ` +
      'the forest did with it.',
  );

  return caveats;
}

// ------------------------------------------------------------- the accounts

const ID = {
  sharedAxis: '3f7a1c20-5e44-4b8a-9d11-0a2b6c8e1001',
  unrelated: '3f7a1c20-5e44-4b8a-9d11-0a2b6c8e1002',
  statisticalOnly: '3f7a1c20-5e44-4b8a-9d11-0a2b6c8e1003',
  modelOutside: '3f7a1c20-5e44-4b8a-9d11-0a2b6c8e1004',
  modelDiluted: '3f7a1c20-5e44-4b8a-9d11-0a2b6c8e1005',
  thin: '3f7a1c20-5e44-4b8a-9d11-0a2b6c8e1006',
  quiet: '3f7a1c20-5e44-4b8a-9d11-0a2b6c8e1007',
};

const QUIET_SIGNALS = [
  signal('amount_outlier', WEIGHTS.amount_outlier, {
    statistic: 1.9,
    explanation: 'Modified z of 1.9 against a median of 61.00, on a MAD-based scale of 14.20. Below the 3.5 flagging point.',
  }),
  signal('velocity', WEIGHTS.velocity, {
    statistic: 1.2,
    explanation: '9 payments in the last 24h against a baseline rate of 4.4/day gives a surprisal of 1.2. Below the 3.0 flagging point.',
  }),
  signal('burst', WEIGHTS.burst, {
    statistic: 0.8,
    explanation: 'Activity spread across 7 windows where the rate predicts 6.2; surprisal 0.8. Below the 3.0 flagging point.',
  }),
  signal('reconciliation_mismatch_rate', WEIGHTS.reconciliation_mismatch_rate, {
    statistic: 0.3,
    explanation: '0 of 44 payments failed reconciliation; surprisal 0.3. Below the 3.0 flagging point.',
  }),
  signal('refund_reversal_rate', WEIGHTS.refund_reversal_rate, {
    statistic: 0.5,
    explanation: '1 of 44 payments was refunded or reversed; surprisal 0.5. Below the 3.0 flagging point.',
  }),
];

const ACCOUNTS = {
  // Both layers elevated, and the model's chief driver shares the amount axis.
  [ID.sharedAxis]: {
    composite: 0.78,
    mlScore: 0.81,
    applicableSignals: 4,
    wellEvidenced: true,
    applicableWeight: 0.85,
    contributions: [
      signal('amount_outlier', WEIGHTS.amount_outlier, {
        fired: true, statistic: 11.4, score: 1, contribution: 0.71,
        explanation: 'The largest of 34 recent payments is 11.4 modified-z from this account’s median of 42.00, against a MAD-based scale of 6.10. Flags at 3.5, saturates at 10.0.',
        subjectId: '9c2d4e61-77aa-4f10-8c33-15b2d9e40aa1',
      }),
      signal('velocity', WEIGHTS.velocity, {
        fired: true, statistic: 4.6, score: 0.32, contribution: 0.18,
        explanation: '34 payments in the last 24h against a baseline rate of 11.2/day gives a surprisal of 4.6. Flags at 3.0, saturates at 8.0.',
      }),
      signal('burst', WEIGHTS.burst, {
        fired: true, statistic: 3.4, score: 0.08, contribution: 0.11,
        explanation: 'Activity clustered into 3 windows where the rate predicts 8.1; surprisal 3.4. Flags at 3.0, saturates at 8.0.',
      }),
      signal('reconciliation_mismatch_rate', WEIGHTS.reconciliation_mismatch_rate, {
        statistic: 0.6,
        explanation: '0 of 34 payments failed reconciliation; surprisal 0.6. Below the 3.0 flagging point.',
      }),
      signal('refund_reversal_rate', WEIGHTS.refund_reversal_rate, {
        applicable: false,
        explanation: 'Only 2 settled payments in the baseline window, below the 10 needed for a rate to mean anything. Nothing to judge.',
      }),
    ],
    attribution: {
      values: { amountModifiedZ: 11.4, recentPaymentCount: 34, log10LargestRecentAmount: 3.41, velocitySurprisal: 4.6, burstSurprisal: 3.4, mismatchSurprisal: 0.6, historicalAmountPercentile: 0.99, dataCompleteness: 0.8 },
      percentiles: { amountModifiedZ: 0.997, recentPaymentCount: 0.981, log10LargestRecentAmount: 0.994, velocitySurprisal: 0.912, burstSurprisal: 0.874, mismatchSurprisal: 0.336, historicalAmountPercentile: 0.99, dataCompleteness: 0.118 },
      excess: { amountModifiedZ: 1.412, log10LargestRecentAmount: 0.774, recentPaymentCount: 0.512 },
    },
    llmNarrative:
      'This account’s largest recent payment sits 11.4 modified-z from its own median of 42.00, and the ' +
      'forest isolated it chiefly on that same measurement, which supplied 52% of the isolation. Its ' +
      'velocity also fired, at a surprisal of 4.6 against a baseline of 11.2 payments a day. The model ' +
      'additionally leaned on log10LargestRecentAmount — the absolute size of that payment — which no ' +
      'statistical signal measures, because they all judge an account against itself. Both layers are ' +
      'elevated and they overlap on the amount axis. Neither score is validated against ground truth.',
  },

  // Both elevated, and the model isolated only on axes no signal shares.
  [ID.unrelated]: {
    composite: 0.64,
    mlScore: 0.73,
    applicableSignals: 5,
    wellEvidenced: true,
    applicableWeight: 1.0,
    contributions: [
      signal('burst', WEIGHTS.burst, {
        fired: true, statistic: 6.8, score: 0.76, contribution: 0.62,
        explanation: 'Activity clustered into 2 windows where the rate predicts 14.3; surprisal 6.8. Flags at 3.0, saturates at 8.0.',
      }),
      signal('velocity', WEIGHTS.velocity, {
        fired: true, statistic: 4.1, score: 0.22, contribution: 0.38,
        explanation: '31 payments in the last 24h against a baseline rate of 16.7/day gives a surprisal of 4.1. Flags at 3.0, saturates at 8.0.',
      }),
      signal('amount_outlier', WEIGHTS.amount_outlier, {
        statistic: 2.1,
        explanation: 'Modified z of 2.1 against a median of 88.00, on a MAD-based scale of 19.40. Below the 3.5 flagging point.',
      }),
      signal('reconciliation_mismatch_rate', WEIGHTS.reconciliation_mismatch_rate, {
        statistic: 0.4,
        explanation: '0 of 31 payments failed reconciliation; surprisal 0.4. Below the 3.0 flagging point.',
      }),
      signal('refund_reversal_rate', WEIGHTS.refund_reversal_rate, {
        statistic: 1.4,
        explanation: '2 of 31 payments were refunded or reversed; surprisal 1.4. Below the 3.0 flagging point.',
      }),
    ],
    attribution: {
      // Isolating only on OUTSIDE features, so nothing corroborates: the case
      // that looks like agreement in the scores and is not.
      values: { log10SecondsSinceLastPayment: 6.91, log10LargestRecentAmount: 1.04, burstSurprisal: 6.8, velocitySurprisal: 4.1, dataCompleteness: 1.0 },
      percentiles: { log10SecondsSinceLastPayment: 0.991, log10LargestRecentAmount: 0.038, burstSurprisal: 0.967, velocitySurprisal: 0.902, dataCompleteness: 0.64 },
      excess: { log10SecondsSinceLastPayment: 0.981, log10LargestRecentAmount: 0.664 },
    },
  },

  // Statistically elevated, ordinary across the population.
  [ID.statisticalOnly]: {
    composite: 0.69,
    mlScore: 0.44,
    applicableSignals: 4,
    wellEvidenced: true,
    applicableWeight: 0.85,
    contributions: [
      signal('amount_outlier', WEIGHTS.amount_outlier, {
        fired: true, statistic: 8.2, score: 0.72, contribution: 0.68,
        explanation: 'The largest of 26 recent payments is 8.2 modified-z from this account’s median of 120.00, against a MAD-based scale of 8.90. Flags at 3.5, saturates at 10.0.',
        subjectId: '9c2d4e61-77aa-4f10-8c33-15b2d9e40dd4',
      }),
      signal('velocity', WEIGHTS.velocity, {
        fired: true, statistic: 3.9, score: 0.18, contribution: 0.32,
        explanation: '26 payments in the last 24h against a baseline rate of 12.9/day gives a surprisal of 3.9. Flags at 3.0, saturates at 8.0.',
      }),
      signal('burst', WEIGHTS.burst, {
        statistic: 1.4,
        explanation: 'Activity spread across 6 windows where the rate predicts 7.4; surprisal 1.4. Below the 3.0 flagging point.',
      }),
      signal('reconciliation_mismatch_rate', WEIGHTS.reconciliation_mismatch_rate, {
        statistic: 0.2,
        explanation: '0 of 26 payments failed reconciliation; surprisal 0.2. Below the 3.0 flagging point.',
      }),
      signal('refund_reversal_rate', WEIGHTS.refund_reversal_rate, {
        applicable: false,
        explanation: 'Only 4 settled payments in the baseline window, below the 10 needed for a rate to mean anything. Nothing to judge.',
      }),
    ],
    attribution: {
      values: { amountModifiedZ: 8.2, recentPaymentCount: 26, historicalAmountPercentile: 0.96, velocitySurprisal: 3.9, dataCompleteness: 0.8 },
      percentiles: { amountModifiedZ: 0.972, recentPaymentCount: 0.944, historicalAmountPercentile: 0.96, velocitySurprisal: 0.887, dataCompleteness: 0.118 },
      excess: { amountModifiedZ: 0.412, recentPaymentCount: 0.208 },
    },
  },

  // The row Phase 9 built the parallel score to surface.
  [ID.modelOutside]: {
    composite: 0.19,
    mlScore: 0.77,
    applicableSignals: 5,
    wellEvidenced: true,
    applicableWeight: 1.0,
    contributions: QUIET_SIGNALS,
    attribution: {
      values: { log10SecondsSinceLastPayment: 7.12, log10LargestRecentAmount: 0.78, amountModifiedZ: 1.9, dataCompleteness: 1.0 },
      percentiles: { log10SecondsSinceLastPayment: 0.996, log10LargestRecentAmount: 0.021, amountModifiedZ: 0.612, dataCompleteness: 0.64 },
      excess: { log10SecondsSinceLastPayment: 1.104, log10LargestRecentAmount: 0.842 },
    },
  },

  // The dilution case: a signal fired and the composite did not carry it.
  [ID.modelDiluted]: {
    composite: 0.31,
    mlScore: 0.68,
    applicableSignals: 5,
    wellEvidenced: true,
    applicableWeight: 1.0,
    contributions: [
      signal('amount_outlier', WEIGHTS.amount_outlier, {
        fired: true, statistic: 5.2, score: 0.26, contribution: 0.83,
        explanation: 'The largest of 19 recent payments is 5.2 modified-z from this account’s median of 18.50, against a MAD-based scale of 3.40. Flags at 3.5, saturates at 10.0.',
        subjectId: '9c2d4e61-77aa-4f10-8c33-15b2d9e40bb2',
      }),
      signal('velocity', WEIGHTS.velocity, {
        fired: true, statistic: 3.2, score: 0.04, contribution: 0.17,
        explanation: '19 payments in the last 24h against a baseline rate of 13.8/day gives a surprisal of 3.2. Flags at 3.0, saturates at 8.0.',
      }),
      signal('burst', WEIGHTS.burst, {
        statistic: 1.1,
        explanation: 'Activity spread across 5 windows where the rate predicts 5.8; surprisal 1.1. Below the 3.0 flagging point.',
      }),
      signal('reconciliation_mismatch_rate', WEIGHTS.reconciliation_mismatch_rate, {
        statistic: 0.2,
        explanation: '0 of 19 payments failed reconciliation; surprisal 0.2. Below the 3.0 flagging point.',
      }),
      signal('refund_reversal_rate', WEIGHTS.refund_reversal_rate, {
        statistic: 0.9,
        explanation: '1 of 19 payments was refunded or reversed; surprisal 0.9. Below the 3.0 flagging point.',
      }),
    ],
    attribution: {
      // amountModifiedZ isolates, and amount_outlier fired: corroboration
      // alongside ML_ONLY is the dilution case.
      values: { amountModifiedZ: 5.2, log10SecondsSinceLastPayment: 6.4, velocitySurprisal: 3.2, dataCompleteness: 1.0 },
      percentiles: { amountModifiedZ: 0.961, log10SecondsSinceLastPayment: 0.974, velocitySurprisal: 0.851, dataCompleteness: 0.64 },
      excess: { amountModifiedZ: 0.884, log10SecondsSinceLastPayment: 0.851 },
    },
  },

  // One signal able to judge, saturated. Elevated on very little evidence.
  [ID.thin]: {
    composite: 0.62,
    mlScore: 0.52,
    applicableSignals: 1,
    wellEvidenced: false,
    applicableWeight: 0.25,
    contributions: [
      signal('amount_outlier', WEIGHTS.amount_outlier, {
        fired: true, statistic: 9.8, score: 1, contribution: 1,
        explanation: 'The largest of 6 recent payments is 9.8 modified-z from this account’s median of 5.00, against a MAD-based scale of 1.20. Flags at 3.5, saturates at 10.0.',
        subjectId: '9c2d4e61-77aa-4f10-8c33-15b2d9e40cc3',
      }),
      signal('velocity', WEIGHTS.velocity, {
        applicable: false,
        explanation: 'Fewer than 7 days of history, so no baseline rate exists. Nothing to judge.',
      }),
      signal('burst', WEIGHTS.burst, {
        applicable: false,
        explanation: 'Fewer than 7 days of history, so no baseline rate exists. Nothing to judge.',
      }),
      signal('reconciliation_mismatch_rate', WEIGHTS.reconciliation_mismatch_rate, {
        applicable: false,
        explanation: 'No settled payments in the baseline window. Nothing to judge.',
      }),
      signal('refund_reversal_rate', WEIGHTS.refund_reversal_rate, {
        applicable: false,
        explanation: 'No settled payments in the baseline window. Nothing to judge.',
      }),
    ],
    attribution: {
      values: { amountModifiedZ: 9.8, dataCompleteness: 0.2, log10LargestRecentAmount: 1.69, recentPaymentCount: 6 },
      percentiles: { amountModifiedZ: 0.989, dataCompleteness: 0.008, log10LargestRecentAmount: 0.44, recentPaymentCount: 0.41 },
      excess: { amountModifiedZ: 0.621, dataCompleteness: 0.318 },
    },
  },

  // Quiet in both layers, included so the state has a screen of its own.
  [ID.quiet]: {
    composite: 0.12,
    mlScore: 0.47,
    applicableSignals: 5,
    wellEvidenced: true,
    applicableWeight: 1.0,
    contributions: QUIET_SIGNALS,
    attribution: {
      values: { amountModifiedZ: 1.9, dataCompleteness: 1.0 },
      percentiles: { amountModifiedZ: 0.612, dataCompleteness: 0.64 },
      excess: {},
    },
  },
};

const LABELS = {
  [ID.sharedAxis]: [
    {
      id: 'b1000000-0000-4000-8000-000000000001',
      accountId: ID.sharedAxis,
      verdict: 'ANOMALOUS',
      source: 'DISPUTE_FEED',
      stratum: 'FLAGGED',
      reviewer: 'scheme:visa',
      scoresVisible: false,
      labelledAsOf: '2026-07-04T11:20:00Z',
      observedAt: '2026-09-02T11:20:00Z',
      latencySeconds: 5184000,
      evidenceId: '9c2d4e61-77aa-4f10-8c33-15b2d9e40aa1',
      notes: 'Chargeback reason FRAUDULENT_TRANSACTION, matured 60 days after the payment.',
    },
  ],
  [ID.statisticalOnly]: [
    {
      id: 'b1000000-0000-4000-8000-000000000002',
      accountId: ID.statisticalOnly,
      verdict: 'BENIGN',
      source: 'HUMAN_REVIEW',
      stratum: 'FLAGGED',
      reviewer: 'reviewer-2',
      scoresVisible: false,
      labelledAsOf: '2026-09-08T15:02:00Z',
      observedAt: '2026-09-08T15:02:00Z',
      latencySeconds: 0,
      evidenceId: null,
      notes: 'Payroll run. Large and regular, and regular is the part the account’s own history missed.',
    },
    {
      id: 'b1000000-0000-4000-8000-000000000003',
      accountId: ID.statisticalOnly,
      verdict: 'ANOMALOUS',
      source: 'HUMAN_REVIEW',
      stratum: 'FLAGGED',
      reviewer: 'reviewer-5',
      scoresVisible: true,
      labelledAsOf: '2026-09-10T09:41:00Z',
      observedAt: '2026-09-10T09:41:00Z',
      latencySeconds: 0,
      evidenceId: null,
      notes: 'Disagrees with reviewer-2, and saw the scores before judging.',
    },
  ],
  [ID.modelDiluted]: [
    {
      id: 'b1000000-0000-4000-8000-000000000004',
      accountId: ID.modelDiluted,
      verdict: 'ANOMALOUS',
      source: 'SYNTHETIC',
      stratum: 'FLAGGED',
      reviewer: 'generator',
      scoresVisible: true,
      labelledAsOf: '2026-09-01T00:00:00Z',
      observedAt: '2026-09-01T00:00:00Z',
      latencySeconds: 0,
      evidenceId: null,
      notes: 'Generated by the Phase 12 benchmark with known ground truth.',
    },
  ],
  [ID.modelOutside]: [
    {
      id: 'b1000000-0000-4000-8000-000000000005',
      accountId: ID.modelOutside,
      verdict: 'UNCLEAR',
      source: 'HUMAN_REVIEW',
      stratum: 'AUDIT',
      reviewer: 'reviewer-2',
      scoresVisible: false,
      labelledAsOf: '2026-09-11T13:30:00Z',
      observedAt: '2026-09-11T13:30:00Z',
      latencySeconds: 0,
      evidenceId: null,
      notes: 'Forty-four small payments to counterparties I cannot assess from the ledger alone.',
    },
  ],
};

// --------------------------------------------------------------- responses

function buildAttributions(accountId) {
  return attributionsFor(ACCOUNTS[accountId].attribution);
}

function digest(accountId, reconciliation) {
  const account = ACCOUNTS[accountId];
  return {
    summary:
      `The statistical layer scored this account ${account.composite.toFixed(2)} over ` +
      `${account.applicableSignals} applicable signal(s). ${reconciliation.narrative} Neither score ` +
      'is validated against ground truth.',
    agreement: reconciliation.agreement,
    corroborated: reconciliation.corroborated,
    statisticalDrivers: reconciliation.statisticalDrivers,
    modelDrivers: reconciliation.modelDrivers,
    modelDriversOutsideStatisticalView: reconciliation.modelDriversOutsideView,
    detail: `/detection/accounts/${accountId}/explanation`,
  };
}

function assessment(accountId) {
  const account = ACCOUNTS[accountId];
  const reconciliation = reconcile(account, buildAttributions(accountId));

  return {
    accountId,
    asOf: AS_OF,
    statisticalScore: account.composite,
    applicableSignals: account.applicableSignals,
    wellEvidenced: account.wellEvidenced,
    ml: {
      available: true,
      score: account.mlScore,
      agreement: reconciliation.agreement,
      unavailableReason: null,
      model: MODEL,
    },
    signals: contributionsOf(account.contributions),
    explanation: digest(accountId, reconciliation),
  };
}

function explanation(accountId, preferTemplate) {
  const account = ACCOUNTS[accountId];
  const attributions = buildAttributions(accountId);
  const reconciliation = reconcile(account, attributions);
  const useModelProse = !preferTemplate && Boolean(account.llmNarrative);

  return {
    accountId,
    asOf: AS_OF,
    statistical: {
      composite: account.composite,
      applicableSignals: account.applicableSignals,
      wellEvidenced: account.wellEvidenced,
      applicableWeight: account.applicableWeight,
      contributions: contributionsOf(account.contributions),
    },
    ml: {
      score: account.mlScore,
      expectedPathLength: 7.42,
      trainingRows: TRAINING_ROWS,
      model: MODEL,
      attributions,
    },
    mlUnavailableReason: null,
    reconciliation,
    summary: useModelProse ? account.llmNarrative : digest(accountId, reconciliation).summary,
    narrativeSource: useModelProse ? 'LLM' : 'TEMPLATE',
    caveats: caveatsFor(account),
  };
}

/** Composite descending, isolation score as tie-break, account id after that. */
const RANKED = Object.keys(ACCOUNTS).sort((left, right) => {
  const byComposite = ACCOUNTS[right].composite - ACCOUNTS[left].composite;
  if (byComposite !== 0) return byComposite;
  const byMl = ACCOUNTS[right].mlScore - ACCOUNTS[left].mlScore;
  return byMl !== 0 ? byMl : left.localeCompare(right);
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function json(response, body, status = 200) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload),
  });
  response.end(payload);
}

createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://localhost:${PORT}`);
  const path = url.pathname;

  if (path === '/detection/anomalies') {
    const minScore = Number(url.searchParams.get('minScore') ?? 0.5);
    const includeMlOnly = url.searchParams.get('includeMlOnly') !== 'false';

    const rows = RANKED.filter((accountId) => {
      const account = ACCOUNTS[accountId];
      return account.composite >= minScore || (includeMlOnly && account.mlScore >= ML_ELEVATED);
    }).map(assessment);

    return json(response, rows);
  }

  const explanationMatch = path.match(/^\/detection\/accounts\/([^/]+)\/explanation$/);
  if (explanationMatch) {
    const accountId = explanationMatch[1];
    if (!ACCOUNTS[accountId]) return json(response, { error: 'no such account' }, 404);

    const preferTemplate = url.searchParams.get('narrative') === 'template';
    // Only the default path is slow, which is where the real latency is.
    if (!preferTemplate) await sleep(NARRATIVE_DELAY_MS);
    return json(response, explanation(accountId, preferTemplate));
  }

  const labelsMatch = path.match(/^\/validation\/labels\/([^/]+)$/);
  if (labelsMatch) {
    return json(response, LABELS[labelsMatch[1]] ?? []);
  }

  if (path === '/detection/model') {
    return json(response, MODEL);
  }

  json(response, { error: `no mock handler for ${path}` }, 404);
}).listen(PORT, () => {
  process.stdout.write(`Mock LedgerGuard API on http://localhost:${PORT}\n`);
  process.stdout.write(`Accounts: ${RANKED.length}. Default narrative delayed ${NARRATIVE_DELAY_MS}ms.\n`);
});
