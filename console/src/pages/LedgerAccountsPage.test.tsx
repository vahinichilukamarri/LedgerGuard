import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { LedgerAccountsPage } from './LedgerAccountsPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

const ACCOUNT_A = '11111111-1111-1111-1111-111111111111';
const ACCOUNT_B = '22222222-2222-2222-2222-222222222222';

function accountsPage() {
  return {
    content: [
      { id: ACCOUNT_A, name: 'Payer', currency: 'USD', createdAt: '2026-09-10T08:00:00Z' },
      { id: ACCOUNT_B, name: 'Payee', currency: 'USD', createdAt: '2026-09-10T07:00:00Z' },
    ],
    page: 0,
    size: 10,
    totalElements: 2,
    totalPages: 1,
  };
}

function renderAccounts() {
  server.use(
    http.get('/accounts', () => HttpResponse.json(accountsPage())),
    http.get('/accounts/:id/balance', ({ params }) =>
      HttpResponse.json({ accountId: params['id'], currency: 'USD', balanceMinorUnits: 1025, balance: 10.25 }),
    ),
  );
  return renderAt(<LedgerAccountsPage />, '/ledger/accounts', '/ledger/accounts');
}

describe('LedgerAccountsPage', () => {
  it('lists accounts with a derived balance tag', async () => {
    renderAccounts();

    expect(await screen.findByText('Payer')).toBeInTheDocument();
    expect(screen.getByText('Payee')).toBeInTheDocument();
    expect(await screen.findAllByText('Derived')).not.toHaveLength(0);
  });

  it('links each account to its transactions and its detection profile', async () => {
    renderAccounts();
    await screen.findByText('Payer');

    // Mock rows render in the exact order the (mocked) backend returned them: Payer (ACCOUNT_A) first.
    const transactionsLinks = screen.getAllByRole('link', { name: /^Transactions$/ });
    expect(transactionsLinks[0]).toHaveAttribute('href', `/ledger/transactions?accountId=${ACCOUNT_A}`);

    const detectionLinks = screen.getAllByRole('link', { name: /Detection profile/ });
    expect(detectionLinks[0]).toHaveAttribute('href', `/accounts/${ACCOUNT_A}`);
  });

  it('has a New account action in the page header', async () => {
    renderAccounts();
    const link = await screen.findByRole('link', { name: /New account/i });
    expect(link).toHaveAttribute('href', '/ledger/accounts/new');
  });
});
