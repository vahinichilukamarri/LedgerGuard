import { useState, type FormEvent } from 'react';
import { useInjectDispute } from '../../api/queries';
import type { DisputeReason } from '../../api/types';
import { describe } from '../States';
import { ConfirmDialog } from '../ledger/ConfirmDialog';

const REASONS: { value: DisputeReason; label: string; labelBearing: boolean }[] = [
  { value: 'FRAUDULENT', label: 'Fraudulent — the only label-bearing reason', labelBearing: true },
  { value: 'NOT_RECEIVED', label: 'Goods or services never arrived', labelBearing: false },
  { value: 'DUPLICATE', label: 'Billed more than once', labelBearing: false },
  { value: 'AUTHORISATION', label: 'Authorisation problem', labelBearing: false },
  { value: 'OTHER', label: 'Other', labelBearing: false },
];

/**
 * Only `FRAUDULENT` becomes a label — see `DisputeReason.isFraudEvidence`.
 * The other four are recorded because they are real events worth knowing the
 * rate of, and deliberately never feed `/validation/report`.
 */
export function DisputeInjectionForm() {
  const [transactionId, setTransactionId] = useState('');
  const [reason, setReason] = useState<DisputeReason>('FRAUDULENT');
  const [amountMinor, setAmountMinor] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [confirming, setConfirming] = useState(false);
  const mutation = useInjectDispute();

  function requestSubmit(event: FormEvent) {
    event.preventDefault();
    setConfirming(true);
  }

  const selected = REASONS.find((r) => r.value === reason)!;

  return (
    <form className="form" onSubmit={requestSubmit}>
      <label className="field">
        <span className="field-label">Transaction id</span>
        <input
          type="text"
          value={transactionId}
          onChange={(event) => setTransactionId(event.target.value)}
          required
          className="num"
        />
      </label>
      <label className="field">
        <span className="field-label">Reason</span>
        <select value={reason} onChange={(event) => setReason(event.target.value as DisputeReason)}>
          {REASONS.map((r) => (
            <option key={r.value} value={r.value}>
              {r.label}
            </option>
          ))}
        </select>
      </label>
      <div className="form-row">
        <label className="field">
          <span className="field-label">Amount (minor units)</span>
          <input
            type="number"
            value={amountMinor}
            onChange={(event) => setAmountMinor(event.target.value)}
            min={0}
            required
            className="num"
          />
        </label>
        <label className="field">
          <span className="field-label">Currency</span>
          <input
            type="text"
            value={currency}
            onChange={(event) => setCurrency(event.target.value.toUpperCase())}
            maxLength={3}
            minLength={3}
            className="num"
            style={{ width: 90 }}
          />
        </label>
      </div>

      <div className="form-actions">
        <button type="submit" className="btn btn-admin">
          Raise dispute…
        </button>
        {mutation.isSuccess && (
          <span className="form-success">
            Raised {mutation.data.externalId}. Label-bearing: {String(mutation.data.labelBearing)}.
          </span>
        )}
      </div>
      {mutation.isError && <p className="form-error">{describe(mutation.error)}</p>}

      <ConfirmDialog
        open={confirming}
        tone="admin"
        title="Confirm dispute injection"
        description={
          <>
            Raise a <strong>{selected.label}</strong> dispute against transaction{' '}
            <code>{transactionId}</code>? {selected.labelBearing
              ? 'This is the one reason that can become a fraud label once matured.'
              : 'This reason is recorded but never becomes a label.'}
          </>
        }
        confirmLabel="Raise dispute"
        busy={mutation.isPending}
        onConfirm={() => {
          mutation.mutate(
            { transactionId, reason, amountMinor: Number(amountMinor), currency },
            { onSuccess: () => setConfirming(false) },
          );
        }}
        onCancel={() => setConfirming(false)}
      />
    </form>
  );
}
