import { useMutation, useQuery, useQueries, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import {
  createAccount,
  createPayment,
  createRefund,
  createReversal,
  fetchAccountBalance,
  fetchAccounts,
  fetchAnomalies,
  fetchDisputes,
  fetchExplanation,
  fetchIncidents,
  fetchLabels,
  fetchModel,
  fetchReconciliationRuns,
  fetchReviewCensus,
  fetchReviewNext,
  fetchSettlementRecords,
  fetchTransaction,
  fetchTransactions,
  fetchValidationReport,
  injectDispute,
  injectSettlementFault,
  recordLabel,
  resolveIncident,
  runReconciliation,
  type CreatePaymentInput,
  type IncidentFilter,
  type InjectDisputeInput,
  type InjectFaultInput,
  type RecordLabelInput,
} from './client';
import type {
  AccountAssessment,
  AccountExplanation,
  AccountLabel,
  ModelInfo,
  Stratum,
} from './types';

/**
 * Server state, and only server state.
 *
 * There is no client-side cache layer on top of this. Phase 11 already caches
 * narratives on the server, and a second cache here would be a second thing that
 * can be stale, with the console's copy winning — so the query cache holds
 * responses briefly and nothing derives a stored value from them.
 */

const keys = {
  anomalies: (minScore: number, includeMlOnly: boolean) =>
    ['anomalies', minScore, includeMlOnly] as const,
  explanation: (accountId: string, preferTemplate: boolean) =>
    ['explanation', accountId, preferTemplate] as const,
  labels: (accountId: string) => ['labels', accountId] as const,
  model: () => ['model'] as const,
  accounts: (page: number, size: number) => ['accounts', page, size] as const,
  accountBalance: (accountId: string) => ['accountBalance', accountId] as const,
  transactions: (page: number, size: number, accountId?: string) =>
    ['transactions', page, size, accountId ?? null] as const,
  transaction: (transactionId: string) => ['transaction', transactionId] as const,
  reconciliationRuns: (page: number, size: number) => ['reconciliationRuns', page, size] as const,
  incidents: (filter: IncidentFilter) => ['incidents', filter] as const,
  settlementRecords: (transactionId?: string) => ['settlementRecords', transactionId ?? null] as const,
  disputes: () => ['disputes'] as const,
  reviewNext: (stratum: Stratum, blind: boolean) => ['reviewNext', stratum, blind] as const,
  reviewCensus: () => ['reviewCensus'] as const,
  validationReport: (sources?: string[]) => ['validationReport', sources ?? null] as const,
};

export function useAnomalies(minScore: number, includeMlOnly: boolean) {
  return useQuery({
    queryKey: keys.anomalies(minScore, includeMlOnly),
    queryFn: () => fetchAnomalies(minScore, includeMlOnly),
  });
}

/**
 * The deterministic explanation. Fast, and the one the page renders against.
 *
 * Every number on the detail view comes from this query, never from the default
 * one — so the arithmetic is on screen whether or not a model call succeeds, and
 * a slow narrative delays prose rather than evidence.
 */
export function useTemplateExplanation(accountId: string) {
  return useQuery({
    queryKey: keys.explanation(accountId, true),
    queryFn: () => fetchExplanation(accountId, true),
  });
}

/**
 * The default narrative, which may be written by a hosted model.
 *
 * A separate query on purpose. It is the slowest call in the system, and pairing
 * it with the numbers would hold four kilobytes of arithmetic behind a model
 * round trip. Its failure is contained: the template above is already rendered.
 */
export function useDefaultNarrative(accountId: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.explanation(accountId, false),
    queryFn: () => fetchExplanation(accountId, false),
    enabled,
    // The narrative is not evidence, and a model that just timed out will
    // probably time out again. One attempt, then say so.
    retry: false,
  });
}

export function useLabels(accountId: string) {
  return useQuery({
    queryKey: keys.labels(accountId),
    queryFn: () => fetchLabels(accountId),
  });
}

/**
 * Labels for the accounts on the current page, and no others.
 *
 * There is no bulk label endpoint — `GET /validation/labels/{accountId}` is
 * per-account — so a review-status column costs one request per visible row.
 * Bounding it to the rendered page keeps that at a page size rather than at the
 * length of the ranking, and the column header says where the data comes from
 * rather than letting it look like part of the ranking response.
 */
export function usePageLabels(accountIds: string[]): Map<string, UseQueryResult<AccountLabel[]>> {
  const results = useQueries({
    queries: accountIds.map((accountId) => ({
      queryKey: keys.labels(accountId),
      queryFn: () => fetchLabels(accountId),
    })),
  });

  const byAccount = new Map<string, UseQueryResult<AccountLabel[]>>();
  accountIds.forEach((accountId, index) => {
    const result = results[index];
    if (result) {
      byAccount.set(accountId, result as UseQueryResult<AccountLabel[]>);
    }
  });
  return byAccount;
}

/** null is a valid answer: no model has been trained. */
export function useModel() {
  return useQuery<ModelInfo | null>({
    queryKey: keys.model(),
    queryFn: fetchModel,
  });
}

// ------------------------------------------------------------------ accounts

export function useAccounts(page: number, size = 25) {
  return useQuery({
    queryKey: keys.accounts(page, size),
    queryFn: () => fetchAccounts(page, size),
  });
}

export function useAccountBalance(accountId: string) {
  return useQuery({
    queryKey: keys.accountBalance(accountId),
    queryFn: () => fetchAccountBalance(accountId),
  });
}

export function useCreateAccount() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ name, currency }: { name: string; currency: string }) => createAccount(name, currency),
    onSuccess: () => void client.invalidateQueries({ queryKey: ['accounts'] }),
  });
}

// -------------------------------------------------------------- ledger flow

export function useTransactions(page: number, size = 25, accountId?: string) {
  return useQuery({
    queryKey: keys.transactions(page, size, accountId),
    queryFn: () => fetchTransactions(page, size, accountId),
  });
}

export function useTransaction(transactionId: string) {
  return useQuery({
    queryKey: keys.transaction(transactionId),
    queryFn: () => fetchTransaction(transactionId),
  });
}

/**
 * A successful payment moves money between two accounts, so both balances and
 * both accounts' transaction lists are stale the moment it returns — this
 * invalidates broadly rather than trying to name the two accounts involved.
 */
export function useCreatePayment() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CreatePaymentInput) => createPayment(input),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['accountBalance'] });
      void client.invalidateQueries({ queryKey: ['transactions'] });
    },
  });
}

export function useCreateRefund() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      paymentId,
      amount,
      description,
    }: {
      paymentId: string;
      amount: string;
      description?: string;
    }) => createRefund(paymentId, amount, description),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['accountBalance'] });
      void client.invalidateQueries({ queryKey: ['transactions'] });
    },
  });
}

export function useCreateReversal() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ transactionId, description }: { transactionId: string; description?: string }) =>
      createReversal(transactionId, description),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['accountBalance'] });
      void client.invalidateQueries({ queryKey: ['transactions'] });
    },
  });
}

// ----------------------------------------------------------- reconciliation

export function useReconciliationRuns(page: number, size = 20) {
  return useQuery({
    queryKey: keys.reconciliationRuns(page, size),
    queryFn: () => fetchReconciliationRuns(page, size),
  });
}

export function useIncidents(filter: IncidentFilter = {}) {
  return useQuery({
    queryKey: keys.incidents(filter),
    queryFn: () => fetchIncidents(filter),
  });
}

export function useRunReconciliation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => runReconciliation(),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['reconciliationRuns'] });
      void client.invalidateQueries({ queryKey: ['incidents'] });
    },
  });
}

export function useResolveIncident() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (incidentId: string) => resolveIncident(incidentId),
    onSuccess: () => void client.invalidateQueries({ queryKey: ['incidents'] }),
  });
}

// -------------------------------------------------------------- simulation

export function useSettlementRecords(transactionId?: string) {
  return useQuery({
    queryKey: keys.settlementRecords(transactionId),
    queryFn: () => fetchSettlementRecords(transactionId),
  });
}

export function useInjectSettlementFault() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: InjectFaultInput) => injectSettlementFault(input),
    onSuccess: () => void client.invalidateQueries({ queryKey: ['settlementRecords'] }),
  });
}

export function useDisputes() {
  return useQuery({ queryKey: keys.disputes(), queryFn: fetchDisputes });
}

export function useInjectDispute() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: InjectDisputeInput) => injectDispute(input),
    onSuccess: () => void client.invalidateQueries({ queryKey: ['disputes'] }),
  });
}

// --------------------------------------------------------------- validation

export function useReviewNext(stratum: Stratum, blind: boolean) {
  return useQuery({
    queryKey: keys.reviewNext(stratum, blind),
    queryFn: () => fetchReviewNext(stratum, blind),
  });
}

export function useReviewCensus() {
  return useQuery({ queryKey: keys.reviewCensus(), queryFn: fetchReviewCensus });
}

export function useRecordLabel() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: RecordLabelInput) => recordLabel(input),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['reviewNext'] });
      void client.invalidateQueries({ queryKey: ['reviewCensus'] });
      void client.invalidateQueries({ queryKey: ['labels'] });
    },
  });
}

export function useValidationReport(sources?: string[]) {
  return useQuery({
    queryKey: keys.validationReport(sources),
    queryFn: () => fetchValidationReport(sources),
  });
}

export type { AccountAssessment, AccountExplanation, AccountLabel, ModelInfo };
