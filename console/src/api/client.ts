import type {
  Account,
  AccountAssessment,
  AccountBalance,
  AccountExplanation,
  AccountLabel,
  DisputeCreated,
  DisputeReason,
  DisputeRecord,
  FaultOutcome,
  FaultType,
  ModelInfo,
  PageResponse,
  Payment,
  ReconciliationIncident,
  ReconciliationRunResult,
  ReconciliationRunSummary,
  Refund,
  Reversal,
  ReviewCandidate,
  ReviewCensus,
  SettlementRecord,
  SettlementStatus,
  Stratum,
  Transaction,
  TransactionSummary,
  ValidationReport,
} from './types';

/**
 * The HTTP layer, and nothing else.
 *
 * Relative paths throughout: in development Vite proxies them to the Spring
 * application, and in any real deployment the console would be served from the
 * same origin. No base URL to configure, and no CORS asked of the backend.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly url: string,
    message: string,
    readonly details: string[] = [],
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * `ApiExceptionHandler` answers every 4xx with `{error, message, details,
 * timestamp}`. When the body parses to that shape its `message` is worth
 * more than a generic status line — it is the actual reason a write was
 * refused ("account name is required"), not just that one was.
 */
async function errorMessage(response: Response, url: string): Promise<ApiError> {
  try {
    const body = (await response.clone().json()) as { message?: string; details?: string[] };
    if (typeof body.message === 'string') {
      return new ApiError(response.status, url, body.message, body.details ?? []);
    }
  } catch {
    // Not JSON, or not the expected shape — fall through to the status line.
  }
  return new ApiError(response.status, url, `${response.status} ${response.statusText} from ${url}`);
}

async function get<T>(url: string): Promise<T> {
  const result = await getOptional<T>(url);
  if (result === null) {
    throw new ApiError(204, url, `${url} answered 204 No Content, which this caller does not expect`);
  }
  return result;
}

/** Like {@link get}, but a `204 No Content` is a valid answer rather than a thrown error. */
async function getOptional<T>(url: string): Promise<T | null> {
  let response: Response;
  try {
    response = await fetch(url, { headers: { Accept: 'application/json' } });
  } catch (cause) {
    // A network failure and an HTTP error are different things for a reviewer:
    // one means the backend is not reachable, the other means it answered.
    throw new ApiError(0, url, `Could not reach the API at ${url}. Is the backend running?`);
  }

  if (!response.ok) {
    throw await errorMessage(response, url);
  }
  if (response.status === 204) {
    return null;
  }
  return (await response.json()) as T;
}

/**
 * A generator, not a counter: every idempotency-bearing write calls this once
 * per submission, so a retried request (network blip, a doubled click) replays
 * the original result instead of repeating the effect.
 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

async function send<T>(
  method: 'POST',
  url: string,
  options: { body?: unknown; idempotencyKey?: string } = {},
): Promise<T> {
  const { body, idempotencyKey } = options;
  let response: Response;
  try {
    response = await fetch(url, {
      method,
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, url, `Could not reach the API at ${url}. Is the backend running?`);
  }

  if (!response.ok) {
    throw await errorMessage(response, url);
  }
  if (response.status === 204) {
    return null as T;
  }
  return (await response.json()) as T;
}

/**
 * The ranked list.
 *
 * One request for the whole ranking, and **no LLM call anywhere in it**: this
 * endpoint serves the template digest by construction (`DetectionController`
 * generates a model narrative only on the detail endpoint), which is the Phase
 * 11 cost decision the console is built around. Sorting, filtering and paging
 * happen client-side over this array because the endpoint offers no parameters
 * for them.
 */
export function fetchAnomalies(minScore: number, includeMlOnly: boolean): Promise<AccountAssessment[]> {
  const query = new URLSearchParams({
    minScore: String(minScore),
    includeMlOnly: String(includeMlOnly),
  });
  return get<AccountAssessment[]>(`/detection/anomalies?${query}`);
}

/**
 * The full explanation for one account.
 *
 * `preferTemplate` maps to `?narrative=template`, which skips the model. The
 * default path may call a hosted model and is the slowest request in the system;
 * the console fetches the two as separate queries so the template is on screen
 * while the model's prose is still in flight.
 */
export function fetchExplanation(accountId: string, preferTemplate: boolean): Promise<AccountExplanation> {
  const suffix = preferTemplate ? '?narrative=template' : '';
  return get<AccountExplanation>(`/detection/accounts/${accountId}/explanation${suffix}`);
}

/** Every label on an account, oldest and newest alike — a revised verdict does not delete the first. */
export function fetchLabels(accountId: string): Promise<AccountLabel[]> {
  return get<AccountLabel[]>(`/validation/labels/${accountId}`);
}

/** The loaded model, or null. A 404 here means "none trained", which is not an error. */
export async function fetchModel(): Promise<ModelInfo | null> {
  try {
    return await get<ModelInfo>('/detection/model');
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

// ------------------------------------------------------------------ accounts

export function fetchAccounts(page: number, size = 25): Promise<PageResponse<Account>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return get<PageResponse<Account>>(`/accounts?${query}`);
}

export function fetchAccountBalance(accountId: string): Promise<AccountBalance> {
  return get<AccountBalance>(`/accounts/${accountId}/balance`);
}

export function createAccount(name: string, currency: string): Promise<Account> {
  return send<Account>('POST', '/accounts', { body: { name, currency } });
}

// --------------------------------------------------------------- ledger flow

export interface CreatePaymentInput {
  sourceAccountId: string;
  destinationAccountId: string;
  amount: string;
  currency: string;
  description?: string;
}

export function createPayment(input: CreatePaymentInput): Promise<Payment> {
  return send<Payment>('POST', '/payments', { body: input, idempotencyKey: newIdempotencyKey() });
}

export function createRefund(paymentId: string, amount: string, description?: string): Promise<Refund> {
  return send<Refund>('POST', `/payments/${paymentId}/refunds`, {
    body: { amount, description },
    idempotencyKey: newIdempotencyKey(),
  });
}

/** `Idempotency-Key` is not optional here server-side: a reversal is single-use and a bare retry 422s. */
export function createReversal(transactionId: string, description?: string): Promise<Reversal> {
  return send<Reversal>('POST', `/transactions/${transactionId}/reversals`, {
    body: { description },
    idempotencyKey: newIdempotencyKey(),
  });
}

export function fetchTransactions(
  page: number,
  size = 25,
  accountId?: string,
): Promise<PageResponse<TransactionSummary>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (accountId) {
    query.set('accountId', accountId);
  }
  return get<PageResponse<TransactionSummary>>(`/transactions?${query}`);
}

export function fetchTransaction(transactionId: string): Promise<Transaction> {
  return get<Transaction>(`/transactions/${transactionId}`);
}

// ----------------------------------------------------------- reconciliation

export function runReconciliation(): Promise<ReconciliationRunResult> {
  return send<ReconciliationRunResult>('POST', '/reconciliation/runs');
}

export function fetchReconciliationRuns(page: number, size = 20): Promise<PageResponse<ReconciliationRunSummary>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return get<PageResponse<ReconciliationRunSummary>>(`/reconciliation/runs?${query}`);
}

export interface IncidentFilter {
  type?: string;
  severity?: string;
  status?: string;
  transactionId?: string;
}

/** Unbounded — the backend offers no pagination here. See CONSOLE_REPORT limitations. */
export function fetchIncidents(filter: IncidentFilter = {}): Promise<ReconciliationIncident[]> {
  const query = new URLSearchParams();
  if (filter.type) query.set('type', filter.type);
  if (filter.severity) query.set('severity', filter.severity);
  if (filter.status) query.set('status', filter.status);
  if (filter.transactionId) query.set('transactionId', filter.transactionId);
  const suffix = query.toString() ? `?${query}` : '';
  return get<ReconciliationIncident[]>(`/reconciliation/incidents${suffix}`);
}

export function resolveIncident(incidentId: string): Promise<ReconciliationIncident> {
  return send<ReconciliationIncident>('POST', `/reconciliation/incidents/${incidentId}/resolve`);
}

// -------------------------------------------------------------- simulation
//
// `/admin/...` only. Namespaced apart from the ledger endpoints above on
// purpose — see the SimulationPage admin styling and confirmation flow.

export interface InjectFaultInput {
  type: FaultType;
  transactionId?: string;
  amountDeltaMinor?: number;
  newStatus?: SettlementStatus;
  amountMinor?: number;
  currency?: string;
}

export function injectSettlementFault(input: InjectFaultInput): Promise<FaultOutcome> {
  return send<FaultOutcome>('POST', '/admin/settlement/faults', { body: input });
}

export function fetchSettlementRecords(transactionId?: string): Promise<SettlementRecord[]> {
  const suffix = transactionId ? `?transactionId=${transactionId}` : '';
  return get<SettlementRecord[]>(`/admin/settlement/records${suffix}`);
}

export interface InjectDisputeInput {
  transactionId: string;
  reason: DisputeReason;
  amountMinor: number;
  currency?: string;
  raisedAt?: string;
}

export function injectDispute(input: InjectDisputeInput): Promise<DisputeCreated> {
  return send<DisputeCreated>('POST', '/admin/disputes', { body: input });
}

export function fetchDisputes(): Promise<DisputeRecord[]> {
  return get<DisputeRecord[]>('/admin/disputes');
}

// --------------------------------------------------------------- validation

/** null means the pool for this stratum is empty right now, not a failure. */
export function fetchReviewNext(stratum: Stratum, blind: boolean): Promise<ReviewCandidate | null> {
  const query = new URLSearchParams({ stratum, blind: String(blind) });
  return getOptional<ReviewCandidate>(`/validation/review/next?${query}`);
}

export function fetchReviewCensus(): Promise<ReviewCensus> {
  return get<ReviewCensus>('/validation/review/census');
}

export interface RecordLabelInput {
  accountId: string;
  verdict: 'ANOMALOUS' | 'BENIGN' | 'UNCLEAR';
  reviewer: string;
  stratum: Stratum;
  scoresVisible: boolean;
  notes?: string;
}

export function recordLabel(input: RecordLabelInput): Promise<AccountLabel> {
  return send<AccountLabel>('POST', '/validation/labels', { body: input });
}

export function fetchValidationReport(sources?: string[]): Promise<ValidationReport> {
  const query = sources && sources.length > 0 ? `?${sources.map((s) => `sources=${s}`).join('&')}` : '';
  return get<ValidationReport>(`/validation/report${query}`);
}
