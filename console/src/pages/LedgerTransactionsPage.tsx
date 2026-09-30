import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Icon } from '../components/icons/Icon';
import { TransactionsPanel } from '../components/ledger/TransactionsPanel';

export function LedgerTransactionsPage() {
  return (
    <div>
      <PageHeader
        icon="transactions"
        title="Transactions"
        description={
          <>
            <code>GET /transactions</code>, filterable by account, offset-paginated 10 at a time. Every row
            is an immutable posted transaction — postings are never edited, only reversed by posting a new,
            negating transaction.
          </>
        }
        action={
          <Link className="btn btn-primary" to="/ledger/payments">
            <Icon name="plus" /> New payment
          </Link>
        }
      />

      <section className="card">
        <TransactionsPanel />
      </section>
    </div>
  );
}
