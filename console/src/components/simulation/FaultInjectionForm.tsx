import { useState, type FormEvent } from 'react';
import { useInjectSettlementFault } from '../../api/queries';
import type { InjectFaultInput } from '../../api/client';
import type { FaultType, SettlementStatus } from '../../api/types';
import { describe } from '../States';
import { ConfirmDialog } from '../ledger/ConfirmDialog';

const FAULT_TYPES: { value: FaultType; label: string; produces: string }[] = [
  { value: 'DROP_SETTLEMENT', label: 'Drop settlement', produces: 'MISSING_SETTLEMENT' },
  { value: 'RESTATE_AMOUNT', label: 'Restate amount', produces: 'AMOUNT_MISMATCH' },
  { value: 'DUPLICATE_SETTLEMENT', label: 'Duplicate settlement', produces: 'DUPLICATE_SETTLEMENT' },
  { value: 'CHANGE_STATUS', label: 'Change status', produces: 'STATUS_MISMATCH' },
  { value: 'PHANTOM_SETTLEMENT', label: 'Phantom settlement', produces: 'UNEXPECTED_EXTERNAL_TRANSACTION' },
];
const STATUSES: SettlementStatus[] = ['SETTLED', 'PENDING', 'FAILED'];

/**
 * Every fault type but `PHANTOM_SETTLEMENT` targets a real transaction id —
 * find one on `/ledger` first. `PHANTOM_SETTLEMENT` invents an external
 * movement referencing nothing internal, so it takes an amount and currency
 * instead.
 */
export function FaultInjectionForm() {
  const [type, setType] = useState<FaultType>('DROP_SETTLEMENT');
  const [transactionId, setTransactionId] = useState('');
  const [amountDeltaMinor, setAmountDeltaMinor] = useState('');
  const [newStatus, setNewStatus] = useState<SettlementStatus>('PENDING');
  const [amountMinor, setAmountMinor] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [confirming, setConfirming] = useState(false);
  const mutation = useInjectSettlementFault();

  function requestSubmit(event: FormEvent) {
    event.preventDefault();
    setConfirming(true);
  }

  function confirm() {
    const input: InjectFaultInput = { type };
    if (type !== 'PHANTOM_SETTLEMENT') {
      input.transactionId = transactionId;
    }
    if (type === 'RESTATE_AMOUNT') {
      input.amountDeltaMinor = Number(amountDeltaMinor);
    }
    if (type === 'CHANGE_STATUS') {
      input.newStatus = newStatus;
    }
    if (type === 'PHANTOM_SETTLEMENT') {
      input.amountMinor = Number(amountMinor);
      input.currency = currency;
    }
    mutation.mutate(input, { onSuccess: () => setConfirming(false) });
  }

  const selected = FAULT_TYPES.find((f) => f.value === type)!;

  return (
    <form className="form" onSubmit={requestSubmit}>
      <label className="field">
        <span className="field-label">Fault type</span>
        <select value={type} onChange={(event) => setType(event.target.value as FaultType)}>
          {FAULT_TYPES.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </select>
        <span className="field-hint">Reconciliation should report {selected.produces}.</span>
      </label>

      {type !== 'PHANTOM_SETTLEMENT' && (
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
      )}

      {type === 'RESTATE_AMOUNT' && (
        <label className="field">
          <span className="field-label">Amount delta (minor units, negative = processor settled less)</span>
          <input
            type="number"
            value={amountDeltaMinor}
            onChange={(event) => setAmountDeltaMinor(event.target.value)}
            required
            className="num"
          />
        </label>
      )}

      {type === 'CHANGE_STATUS' && (
        <label className="field">
          <span className="field-label">New status</span>
          <select value={newStatus} onChange={(event) => setNewStatus(event.target.value as SettlementStatus)}>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
      )}

      {type === 'PHANTOM_SETTLEMENT' && (
        <div className="form-row">
          <label className="field">
            <span className="field-label">Amount (minor units)</span>
            <input
              type="number"
              value={amountMinor}
              onChange={(event) => setAmountMinor(event.target.value)}
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
      )}

      <div className="form-actions">
        <button type="submit" className="btn btn-admin">
          Inject fault…
        </button>
        {mutation.isSuccess && <span className="form-success">{mutation.data.outcome}</span>}
      </div>
      {mutation.isError && <p className="form-error">{describe(mutation.error)}</p>}

      <ConfirmDialog
        open={confirming}
        tone="admin"
        title="Confirm fault injection"
        description={
          <>
            This injects a <strong>{selected.label}</strong> fault into the simulated processor. It does not
            touch the real ledger, but the next reconciliation run will pick it up and file an incident. This
            cannot be undone from this screen.
          </>
        }
        confirmLabel="Inject fault"
        busy={mutation.isPending}
        onConfirm={confirm}
        onCancel={() => setConfirming(false)}
      />
    </form>
  );
}
