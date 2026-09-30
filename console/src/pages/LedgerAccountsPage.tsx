import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Icon } from '../components/icons/Icon';
import { AccountsPanel } from '../components/ledger/AccountsPanel';

export function LedgerAccountsPage() {
  return (
    <div>
      <PageHeader
        icon="accounts"
        title="Accounts"
        description={
          <>
            <code>GET /accounts</code>, offset-paginated 10 at a time, newest first. Balance is recomputed
            from postings on every request — never stored — marked <code>Derived</code> below.
          </>
        }
        action={
          <Link className="btn btn-primary" to="/ledger/accounts/new">
            <Icon name="plus" /> New account
          </Link>
        }
      />

      <section className="card">
        <AccountsPanel />
      </section>
    </div>
  );
}
