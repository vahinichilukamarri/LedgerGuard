import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LedgerTransactionsPage } from './LedgerTransactionsPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

const TRANSACTION_ID = '33333333-3333-3333-3333-333333333333';
const ACCOUNT_A = '11111111-1111-1111-1111-111111111111';
const ACCOUNT_B = '22222222-2222-2222-2222-222222222222';

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

describe('LedgerTransactionsPage', () => {
  it('lists transactions', async () => {
    server.use(http.get('/transactions', () => HttpResponse.json(transactionsPage())));
    renderAt(<LedgerTransactionsPage />, '/ledger/transactions', '/ledger/transactions');

    expect(await screen.findByText('invoice 42')).toBeInTheDocument();
  });

  it('reads the accountId filter from the URL, matching a link from the accounts page', async () => {
    let requestedAccountId: string | null = null;
    server.use(
      http.get('/transactions', ({ request }) => {
        requestedAccountId = new URL(request.url).searchParams.get('accountId');
        return HttpResponse.json(transactionsPage());
      }),
    );
    renderAt(
      <LedgerTransactionsPage />,
      '/ledger/transactions',
      `/ledger/transactions?accountId=${ACCOUNT_A}`,
    );

    await screen.findByText('invoice 42');
    expect(requestedAccountId).toBe(ACCOUNT_A);
    expect(screen.getByPlaceholderText(/account id, or leave blank/i)).toHaveValue(ACCOUNT_A);
  });

  it('reverses a transaction from its expanded postings, behind a confirm dialog', async () => {
    server.use(
      http.get('/transactions', () => HttpResponse.json(transactionsPage())),
      http.get('/transactions/:id', () =>
        HttpResponse.json({
          id: TRANSACTION_ID,
          description: 'invoice 42',
          currency: 'USD',
          createdAt: '2026-09-10T08:00:00Z',
          postings: [
            { id: 'p1', accountId: ACCOUNT_A, type: 'CREDIT', amountMinorUnits: 1025, amount: 10.25, currency: 'USD', createdAt: '2026-09-10T08:00:00Z' },
            { id: 'p2', accountId: ACCOUNT_B, type: 'DEBIT', amountMinorUnits: 1025, amount: 10.25, currency: 'USD', createdAt: '2026-09-10T08:00:00Z' },
          ],
        }),
      ),
      http.post('/transactions/:id/reversals', () =>
        HttpResponse.json(
          {
            reversalId: 'r1',
            originalTransactionId: TRANSACTION_ID,
            reversalTransaction: {
              id: '55555555-5555-5555-5555-555555555555',
              description: `Reversal of transaction ${TRANSACTION_ID}`,
              currency: 'USD',
              createdAt: '2026-09-10T09:00:00Z',
              postings: [],
            },
          },
          { status: 201 },
        ),
      ),
    );
    renderAt(<LedgerTransactionsPage />, '/ledger/transactions', '/ledger/transactions');

    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /postings/i }));
    await user.click(await screen.findByRole('button', { name: /reverse transaction/i }));

    const dialog = await screen.findByRole('alertdialog');
    await user.click(screen.getByRole('button', { name: /^reverse$/i }));

    expect(await screen.findByText(/Reversed\. New transaction/)).toBeInTheDocument();
    expect(dialog).not.toBeInTheDocument();
  });
});
