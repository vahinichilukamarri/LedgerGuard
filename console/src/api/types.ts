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
  /** Only `GET /detection/model` carries these; scored responses embed a narrower view. */
  featureNames?: string[];
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

// -------------------------------------------------------------------- paging
//
// Mirrors `config/PageResponse.java`. Every list endpoint added for the
// ledger console returns this shape rather than a bare array, so paging is
// the same request/response contract everywhere it appears.

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ----------------------------------------------------------------- accounts
//
// Source of truth: `accounts/dto/AccountResponse.java`, `AccountBalanceResponse.java`.

export interface Account {
  id: string;
  name: string;
  currency: string;
  createdAt: string;
}

/**
 * `balanceMinorUnits` is the integer the ledger actually holds, derived by
 * summing postings on every read — there is no stored balance column.
 * `balance` is the same figure rendered as a decimal at the API boundary.
 */
export interface AccountBalance {
  accountId: string;
  currency: string;
  balanceMinorUnits: number;
  balance: number;
}

// ------------------------------------------------------------- transactions
//
// Source of truth: `transactions/dto/TransactionResponse.java`,
// `TransactionSummaryResponse.java`, `postings/dto/PostingResponse.java`.

export type PostingType = 'DEBIT' | 'CREDIT';

export interface Posting {
  id: string;
  accountId: string;
  type: PostingType;
  amountMinorUnits: number;
  amount: number;
  currency: string;
  createdAt: string;
}

/** A row of `GET /transactions` — no postings; see `Transaction` for the detail shape. */
export interface TransactionSummary {
  id: string;
  description: string;
  currency: string;
  createdAt: string;
}

/** `GET /transactions/{id}`, and what `Payment`/`Refund`/`Reversal` carry inline. */
export interface Transaction extends TransactionSummary {
  postings: Posting[];
}

// ----------------------------------------------------------------- payments
//
// Source of truth: `payments/dto/PaymentResponse.java`, `refunds/dto/RefundResponse.java`,
// `reversals/dto/ReversalResponse.java`.

export type PaymentStatus = 'PENDING' | 'POSTED' | 'REVERSED';

export interface Payment {
  paymentId: string;
  status: PaymentStatus;
  sourceAccountId: string;
  destinationAccountId: string;
  transaction: Transaction;
}

export interface Refund {
  refundId: string;
  paymentId: string;
  amountMinorUnits: number;
  amount: number;
  currency: string;
  paymentAmountMinorUnits: number;
  refundedTotalMinorUnits: number;
  remainingRefundableMinorUnits: number;
  remainingRefundable: number;
  transaction: Transaction;
}

export interface Reversal {
  reversalId: string;
  originalTransactionId: string;
  reversalTransaction: Transaction;
}

// ------------------------------------------------------------ reconciliation
//
// Source of truth: `reconciliation/dto/IncidentResponse.java`, `RunResponse.java`,
// `RunSummaryResponse.java`, and the `DiscrepancyType`/`Severity`/`IncidentStatus` enums.

export type DiscrepancyType =
  | 'MATCHED'
  | 'MISSING_SETTLEMENT'
  | 'AMOUNT_MISMATCH'
  | 'DUPLICATE_SETTLEMENT'
  | 'STATUS_MISMATCH'
  | 'UNEXPECTED_EXTERNAL_TRANSACTION';

/**
 * The one place a real, calibrated-looking severity is allowed to reach the
 * screen: `Severity` is a backend-computed enum on reconciliation incidents,
 * not a UI guess. Never reuse this palette for a detection score.
 */
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type IncidentStatus = 'OPEN' | 'RESOLVED';

export interface ReconciliationIncident {
  id: string;
  runId: string;
  type: DiscrepancyType;
  severity: Severity;
  status: IncidentStatus;
  transactionId: string | null;
  settlementRecordId: string | null;
  internalAmountMinor: number | null;
  externalAmountMinor: number | null;
  differenceMinor: number | null;
  currency: string | null;
  internalStatus: string | null;
  externalStatus: string | null;
  detail: string;
  createdAt: string;
  resolvedAt: string | null;
}

/** The response to `POST /reconciliation/runs` — a run plus the incidents it just produced. */
export interface ReconciliationRunResult {
  runId: string;
  startedAt: string;
  completedAt: string;
  internalExamined: number;
  externalExamined: number;
  matched: number;
  awaitingSettlement: number;
  discrepancies: number;
  newIncidents: number;
  alreadyOpen: number;
  incidents: ReconciliationIncident[];
}

/**
 * A row of `GET /reconciliation/runs`. Deliberately narrower than
 * `ReconciliationRunResult`: `awaitingSettlement`, `newIncidents` and
 * `alreadyOpen` are computed once, at run time, from state that has since
 * moved on, and `ReconciliationRun` does not persist them.
 */
export interface ReconciliationRunSummary {
  runId: string;
  startedAt: string;
  completedAt: string;
  internalExamined: number;
  externalExamined: number;
  matched: number;
  discrepancies: number;
}

// ---------------------------------------------------------------- simulation
//
// Source of truth: `settlement/dto/SettlementRecordResponse.java`,
// `settlement/FaultType.java`, `settlement/SettlementStatus.java`,
// `validation/DisputeReason.java`, `DisputeController.java`.
//
// Everything in this section is `/admin/...` — the simulated processor and
// card scheme, not the real ledger. Keep it visually and navigationally
// separate; see `SimulationPage`.

export type SettlementStatus = 'SETTLED' | 'PENDING' | 'FAILED';

export type FaultType =
  | 'DROP_SETTLEMENT'
  | 'RESTATE_AMOUNT'
  | 'DUPLICATE_SETTLEMENT'
  | 'CHANGE_STATUS'
  | 'PHANTOM_SETTLEMENT';

export interface SettlementRecord {
  id: string;
  externalId: string;
  externalReference: string;
  amountMinor: number;
  currency: string;
  status: SettlementStatus;
  settledAt: string;
}

export interface FaultOutcome {
  fault: FaultType;
  outcome: string;
}

/** Only `FRAUDULENT` is fraud evidence — see `DisputeReason.isFraudEvidence`. Every other value is recorded and never labels an account. */
export type DisputeReason = 'FRAUDULENT' | 'NOT_RECEIVED' | 'DUPLICATE' | 'AUTHORISATION' | 'OTHER';

export interface DisputeRecord {
  externalId: string;
  transactionReference: string;
  reason: DisputeReason;
  labelBearing: boolean;
  paymentAt: string;
  raisedAt: string;
  latencyDays: number;
}

export interface DisputeCreated {
  disputeId: string;
  externalId: string;
  reason: DisputeReason;
  raisedAt: string;
  labelBearing: boolean;
}

// ------------------------------------------------------------ review queue
//
// Source of truth: `validation/dto/ReviewCandidateResponse.java`, `ReviewQueue.Census`.

/**
 * `statisticalScore`/`mlScore` are null whenever `blind` is true — enforced
 * server-side, not by this type. A blind fetch physically cannot render a
 * score, because the field is absent from the response, not merely hidden.
 */
export interface ReviewCandidate {
  accountId: string;
  stratum: Stratum;
  blind: boolean;
  statisticalScore: number | null;
  mlScore: number | null;
  paymentsInBaseline: number;
}

/** `total` is a derived method on the Java record, not a wire component — computed here, not trusted from JSON. */
export interface ReviewCensus {
  flagged: number;
  unflagged: number;
}

// --------------------------------------------------------- validation report
//
// Source of truth: `validation/dto/ValidationReportResponse.java`,
// `validation/EvaluationReport.java`.

export interface ValidationInterval {
  point: number | null;
  low: number;
  high: number;
  trials: number;
  informative: boolean;
}

export interface ValidationCounts {
  truePositives: number;
  falsePositives: number;
  trueNegatives: number;
  falseNegatives: number;
  labelled: number;
}

export interface ValidationCurvePoint {
  threshold: number;
  precision: number;
  recall: number;
}

export interface ValidationLayer {
  threshold: number;
  counts: ValidationCounts;
  precision: ValidationInterval;
  /** null when no audit stratum exists — recall would otherwise be 1.0 by construction. */
  recall: ValidationInterval | null;
  recallMeasurable: boolean;
  f1: number | null;
  baseRate: number | null;
  lift: number | null;
  averagePrecision: number | null;
  chanceLevel: number | null;
  curve: ValidationCurvePoint[];
}

export interface ValidationLabelCensus {
  total: number;
  decisive: number;
  unclear: number;
  flaggedStratumLabels: number;
  auditLabels: number;
  disputeLabels: number;
  syntheticLabels: number;
  blindLabels: number;
}

export interface ValidationPopulation {
  flagged: number;
  unflagged: number;
  total: number;
}

/** null `rate` means nobody has been re-reviewed — not perfect agreement. */
export interface ValidationReviewerAgreement {
  doublyReviewed: number;
  agreed: number;
  rate: number | null;
}

export interface ValidationAgreementState {
  labelled: number;
  anomalousShare: number | null;
  interval: ValidationInterval;
}

export interface ValidationReport {
  asOf: string;
  warnings: string[];
  quotable: boolean;
  labels: ValidationLabelCensus;
  population: ValidationPopulation;
  reviewerAgreement: ValidationReviewerAgreement;
  statistical: ValidationLayer;
  model: ValidationLayer;
  /** Keyed by `AgreementState` name. */
  agreementStates: Record<string, ValidationAgreementState>;
}
