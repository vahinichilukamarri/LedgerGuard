import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LedgerPage } from './LedgerPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

const ACCOUNT_A = '11111111-1111-1111-1111-111111111111';
const ACCOUNT_B = '22222222-2222-2222-2222-222222222222';
const TRANSACTION_ID = '33333333-3333-3333-3333-333333333333';

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

function transactionsPage() {
  return {
    content: [
      { id: TRANSACTION_ID, description: 'invoice 42', currency: 'USD', createdAt: '2026-09-10T08:00:00Z' },
    ],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  };
}

function renderLedger() {
  server.use(
    http.get('/accounts', () => HttpResponse.json(accountsPage())),
    http.get('/accounts/:id/balance', ({ params }) =>
      HttpResponse.json({ accountId: params['id'], currency: 'USD', balanceMinorUnits: 1025, balance: 10.25 }),
    ),
    http.get('/transactions', () => HttpResponse.json(transactionsPage())),
  );
  return renderAt(<LedgerPage />, '/ledger', '/ledger');
}

describe('LedgerPage', () => {
  it('lists accounts with a derived balance tag, and lists transactions', async () => {
    renderLedger();

    expect(await screen.findByText('Payer')).toBeInTheDocument();
    expect(screen.getByText('Payee')).toBeInTheDocument();
    expect(await screen.findAllByText('Derived')).not.toHaveLength(0);
    expect(await screen.findByText('invoice 42')).toBeInTheDocument();
  });

  it('posts a payment and shows a receipt with a refund action, never a bare balance change', async () => {
    server.use(
      http.post('/payments', () =>
        HttpResponse.json(
          {
            paymentId: '44444444-4444-4444-4444-444444444444',
            status: 'POSTED',
            sourceAccountId: ACCOUNT_A,
            destinationAccountId: ACCOUNT_B,
            transaction: {
              id: TRANSACTION_ID,
              description: 'invoice 42',
              currency: 'USD',
              createdAt: '2026-09-10T08:00:00Z',
              postings: [],
            },
          },
          { status: 201 },
        ),
      ),
    );
    renderLedger();
    await screen.findByText('Payer');

    const user = userEvent.setup();
    const fields = screen.getAllByPlaceholderText('account id');
    await user.type(fields[0]!, ACCOUNT_A);
    await user.type(fields[1]!, ACCOUNT_B);
    await user.type(screen.getByPlaceholderText('0.00'), '10.25');
    await user.click(screen.getByRole('button', { name: /create payment/i }));

    expect(await screen.findByText('Payment posted')).toBeInTheDocument();
    expect(screen.getByText(/refund this payment/i)).toBeInTheDocument();
  });
});
