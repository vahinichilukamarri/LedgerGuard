/**
 * The wire shapes, mirrored by hand from the Java records.
 *
 * Hand-written rather than generated, because the point of writing them out is
 * that every field gets read once by a person who then has to decide how to
 * render it. A generated client would have given the console `percentile` and
 * `share` as two indistinguishable numbers; the comments below are where the
 * distinction between them is recorded on this side of the wire.
 *
 * Source of truth, in order: `detection/dto/AccountAssessmentResponse.java`,
 * `detection/dto/AccountExplanationResponse.java`,
 * `validation/dto/LabelResponse.java`.
 */

export type AgreementState =
  | 'BOTH_QUIET'
  | 'BOTH_ELEVATED'
  | 'STATISTICAL_ONLY'
  | 'ML_ONLY';

export type NarrativeSource = 'TEMPLATE' | 'LLM';

export type Verdict = 'ANOMALOUS' | 'BENIGN' | 'UNCLEAR';

export type LabelSource = 'HUMAN_REVIEW' | 'DISPUTE_FEED' | 'SYNTHETIC';

export type Stratum = 'FLAGGED' | 'AUDIT';

/** Which model produced a score. Travels with every score; a forest score is not recomputable from a formula. */
export interface ModelInfo {
  seed: number;
  trees: number;
  subSampleSize: number;
  trainingAccounts: number;
  trainedAt: string;
  trainedAsOf: string;
}

/**
 * One signal's part in the composite.
 *
 * `contribution` is a **share in [0,1]**, not composite points — Phase 13
 * replaced the weighted mean with a power mean of degree three, so the parts no
 * longer sum to the composite. They sum to 1. `weight` is the fixed, unfitted
 * declaration weight and does not equal the share.
 */
export interface SignalContribution {
  signal: string;
  applicable: boolean;
  fired: boolean;
  /** null where the signal had nothing to measure. Distinct from zero. */
  statistic: number | null;
  score: number;
  weight: number;
  contribution: number;
  explanation: string;
  subjectId: string | null;
}

/**
 * One feature's part in isolating an account.
 *
 * The first group is what the model did. `percentile` and `median` are **not**:
 * they are facts about the training population, supplied because attribution can
 * name a feature but not say which direction it was extreme in. The console
 * renders them in a separate, labelled block for exactly that reason.
 */
export interface FeatureAttribution {
  feature: string;
  index: number;
  value: number;
  splitsPerTree: number;
  isolationBits: number;
  excessBits: number;
  /** share of positive excess bits, in [0,1]. Not a share of the score. */
  share: number;
  /** POPULATION CONTEXT — never render this as attribution. */
  percentile: number;
  /** POPULATION CONTEXT — never render this as attribution. */
  median: number;
}

/**
 * How the two layers relate, stated rather than resolved.
 *
 * `corroborated === false` alongside `BOTH_ELEVATED` is the case the backend
 * calls the hardest one: it looks like agreement in the scores and is not.
 * `corroboratedSignals` non-empty alongside `ML_ONLY` is the dilution case — a
 * signal did fire, and the composite did not carry it over the line.
 */
export interface Reconciliation {
  agreement: AgreementState;
  statisticalDrivers: string[];
  modelDrivers: string[];
  corroboratedSignals: string[];
  modelDriversOutsideView: string[];
  corroborated: boolean;
  narrative: string;
}

// --------------------------------------------------------------- list view

export interface AssessmentMl {
  /** false when no model has been trained. Distinct from a score of zero, which would be a claim. */
  available: boolean;
  score: number | null;
  agreement: AgreementState | null;
  unavailableReason: string | null;
  model: ModelInfo | null;
}

export interface ExplanationDigest {
  summary: string;
  agreement: AgreementState | null;
  corroborated: boolean;
  statisticalDrivers: string[];
  modelDrivers: string[];
  modelDriversOutsideStatisticalView: string[];
  detail: string;
}

/** A row of `GET /detection/anomalies`, and the whole of `GET /detection/accounts/{id}`. */
export interface AccountAssessment {
  accountId: string;
  asOf: string;
  statisticalScore: number;
  applicableSignals: number;
  wellEvidenced: boolean;
  ml: AssessmentMl;
  signals: SignalContribution[];
  explanation: ExplanationDigest;
}

// ------------------------------------------------------------- detail view

export interface StatisticalDetail {
  composite: number;
  applicableSignals: number;
  wellEvidenced: boolean;
  /** How much of the signal weight was able to speak. Since Phase 13 not a denominator. */
  applicableWeight: number;
  contributions: SignalContribution[];
}

export interface MlDetail {
  score: number;
  expectedPathLength: number;
  trainingRows: number;
  model: ModelInfo;
  attributions: FeatureAttribution[];
}

export interface AccountExplanation {
  accountId: string;
  asOf: string;
  statistical: StatisticalDetail;
  /** null when no model has been trained. */
  ml: MlDetail | null;
  mlUnavailableReason: string | null;
  reconciliation: Reconciliation | null;
  summary: string;
  /**
   * TEMPLATE or LLM, and nothing else — deliberately. The API does not
   * distinguish a template served because the model call failed from one served
   * because no model is configured, so neither does the console.
   */
  narrativeSource: NarrativeSource;
  caveats: string[];
}

// ------------------------------------------------------------------ labels

export interface AccountLabel {
  id: string;
  accountId: string;
  verdict: Verdict;
  source: LabelSource;
  stratum: Stratum;
  reviewer: string;
  /** Whether the reviewer could see the detector's scores. An anchored label is weaker evidence. */
  scoresVisible: boolean;
  labelledAsOf: string;
  observedAt: string;
  /** The gap between the behaviour and the truth about it. */
  latencySeconds: number;
  evidenceId: string | null;
  notes: string | null;
}
