import { useState, type FormEvent } from 'react';
import { useCreatePayment, useCreateRefund } from '../../api/queries';
import type { Payment } from '../../api/types';
import { instant } from '../../format';
import { describe } from '../States';
import { ConfirmDialog } from './ConfirmDialog';
import { EvidenceLink } from './EvidenceLink';
import { MoneyAmount } from './MoneyAmount';

/**
 * POST /payments, plus a refund action on the receipt.
 *
 * Refund is per-payment (`POST /payments/{paymentId}/refunds`), and there is
 * no endpoint that lists payments or maps a transaction back to the payment
 * that produced it — so refunding is only reachable here, right after a
 * payment is created, where the paymentId is in hand. A transaction row in
 * the ledger below can be reversed (that endpoint only needs a transaction
 * id) but not refunded from this console.
 */
export function CreatePaymentForm() {
  const [sourceAccountId, setSourceAccountId] = useState('');
  const [destinationAccountId, setDestinationAccountId] = useState('');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [description, setDescription] = useState('');
  const [receipt, setReceipt] = useState<Payment | null>(null);

  const paymentMutation = useCreatePayment();

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    paymentMutation.mutate(
      { sourceAccountId, destinationAccountId, amount, currency, description: description || undefined },
      { onSuccess: (payment) => setReceipt(payment) },
    );
  }

  return (
    <div>
      <form className="form" onSubmit={onSubmit}>
        <div className="form-row">
          <label className="field">
            <span className="field-label">Source account</span>
            <input
              type="text"
              value={sourceAccountId}
              onChange={(event) => setSourceAccountId(event.target.value)}
              required
              placeholder="account id"
              className="num"
            />
          </label>
          <label className="field">
            <span className="field-label">Destination account</span>
            <input
              type="text"
              value={destinationAccountId}
              onChange={(event) => setDestinationAccountId(event.target.value)}
              required
              placeholder="account id"
              className="num"
            />
          </label>
        </div>
        <div className="form-row">
          <label className="field">
            <span className="field-label">Amount</span>
            <input
              type="text"
              inputMode="decimal"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              required
              placeholder="0.00"
              className="num"
              style={{ width: 120 }}
            />
          </label>
          <label className="field">
            <span className="field-label">Currency</span>
            <input
              type="text"
              value={currency}
              onChange={(event) => setCurrency(event.target.value.toUpperCase())}
              required
              maxLength={3}
              minLength={3}
              className="num"
              style={{ width: 90 }}
            />
          </label>
        </div>
        <label className="field">
          <span className="field-label">Description (optional)</span>
          <input
            type="text"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={500}
          />
        </label>
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={paymentMutation.isPending}>
            {paymentMutation.isPending ? 'Posting…' : 'Create payment'}
          </button>
        </div>
        {paymentMutation.isError && <p className="form-error">{describe(paymentMutation.error)}</p>}
      </form>

      {receipt && <PaymentReceipt payment={receipt} />}
    </div>
  );
}

function PaymentReceipt({ payment }: { payment: Payment }) {
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [confirming, setConfirming] = useState(false);
  const refundMutation = useCreateRefund();

  function requestRefund(event: FormEvent) {
    event.preventDefault();
    setConfirming(true);
  }

  function confirmRefund() {
    refundMutation.mutate(
      { paymentId: payment.paymentId, amount, description: description || undefined },
      { onSuccess: () => setConfirming(false) },
    );
  }

  return (
    <div className="stat-block" style={{ marginTop: 18 }}>
      <h3>Payment posted</h3>
      <dl className="facts">
        <dt>Payment</dt>
        <dd>
          <EvidenceLink id={payment.paymentId} />
        </dd>
        <dt>Status</dt>
        <dd>{payment.status}</dd>
        <dt>Transaction</dt>
        <dd>
          <EvidenceLink id={payment.transaction.id} />
        </dd>
        <dt>Posted</dt>
        <dd>{instant(payment.transaction.createdAt)}</dd>
      </dl>

      {payment.status === 'POSTED' && !refundMutation.isSuccess && (
        <form className="form" onSubmit={requestRefund} style={{ marginTop: 16 }}>
          <p className="section-eyebrow">Refund this payment</p>
          <div className="form-row">
            <label className="field">
              <span className="field-label">Amount</span>
              <input
                type="text"
                inputMode="decimal"
                value={amount}
                onChange={(event) => setAmount(event.target.value)}
                required
                placeholder="0.00"
                className="num"
                style={{ width: 120 }}
              />
            </label>
            <label className="field">
              <span className="field-label">Description (optional)</span>
              <input
                type="text"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                maxLength={500}
              />
            </label>
          </div>
          <div className="form-actions">
            <button type="submit" className="btn">
              Refund…
            </button>
          </div>
          {refundMutation.isError && <p className="form-error">{describe(refundMutation.error)}</p>}
        </form>
      )}

      {refundMutation.isSuccess && (
        <p className="form-success" style={{ marginTop: 14 }}>
          Refunded <MoneyAmount amount={refundMutation.data.amount} currency={refundMutation.data.currency} />.
          Remaining refundable:{' '}
          <MoneyAmount
            amount={refundMutation.data.remainingRefundable}
            currency={refundMutation.data.currency}
          />
          .
        </p>
      )}

      <ConfirmDialog
        open={confirming}
        title="Confirm refund"
        description={
          <>
            Refund <strong>{amount || '0'}</strong> {payment.transaction.currency} against payment{' '}
            <code>{payment.paymentId}</code>? This posts a new balanced transaction; it cannot be undone from
            this screen.
          </>
        }
        confirmLabel="Refund"
        busy={refundMutation.isPending}
        onConfirm={confirmRefund}
        onCancel={() => setConfirming(false)}
      />
    </div>
  );
}
