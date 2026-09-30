import { PageHeader } from '../components/PageHeader';
import { CreateAccountForm } from '../components/ledger/CreateAccountForm';

export function LedgerCreateAccountPage() {
  return (
    <div>
      <PageHeader
        icon="create"
        title="Create account"
        description={
          <>
            <code>POST /accounts</code>. A name and a 3-letter ISO-4217 currency code — every payment needs
            two of these, in the same currency, to move money between.
          </>
        }
      />

      <section className="card">
        <CreateAccountForm />
      </section>
    </div>
  );
}
