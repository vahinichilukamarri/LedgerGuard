import { PageHeader } from '../components/PageHeader';
import { CreatePaymentForm } from '../components/ledger/CreatePaymentForm';

export function LedgerPaymentsPage() {
  return (
    <div>
      <PageHeader
        icon="payments"
        title="Payments"
        description={
          <>
            <code>POST /payments</code>. Moves money between two accounts in the same currency and posts a
            balanced transaction — nothing is written if the postings don't net to zero. There is no{' '}
            <code>GET /payments</code>, so this page is create-only: for payment history, see{' '}
            <code>Transactions</code>.
          </>
        }
      />

      <section className="card">
        <CreatePaymentForm />
      </section>
    </div>
  );
}
