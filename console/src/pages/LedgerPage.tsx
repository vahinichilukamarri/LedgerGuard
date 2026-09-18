import { useState } from 'react';
import { AccountsPanel } from '../components/ledger/AccountsPanel';
import { CreatePaymentForm } from '../components/ledger/CreatePaymentForm';
import { TransactionsPanel } from '../components/ledger/TransactionsPanel';

/**
 * Real ledger operations: accounts, posted transactions, payments, refunds
 * and reversals — everything this console can write, against the real
 * backend, none of it simulated. `/simulation` is the deliberately separate
 * admin surface for the fake processor and card scheme.
 */
export function LedgerPage() {
  const [accountIdFilter, setAccountIdFilter] = useState('');

  return (
    <div>
      <h1>Ledger</h1>
      <p className="card-note" style={{ marginBottom: 20 }}>
        Accounts and transactions are read from the real ledger — nothing here is simulated. A balance is
        never stored; it is recomputed from postings on every request, marked <code>Derived</code> below.
      </p>

      <AccountsPanel onSelectAccount={setAccountIdFilter} />

      <section className="card">
        <h2>Create payment</h2>
        <p className="card-note">
          <code>POST /payments</code>. Moves money between two accounts in the same currency and posts a
          balanced transaction — the payment fails and nothing is written if the postings do not net to zero.
        </p>
        <CreatePaymentForm />
      </section>

      <TransactionsPanel accountId={accountIdFilter} onAccountIdChange={setAccountIdFilter} />
    </div>
  );
}
