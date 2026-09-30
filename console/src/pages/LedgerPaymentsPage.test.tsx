import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LedgerPaymentsPage } from './LedgerPaymentsPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

const ACCOUNT_A = '11111111-1111-1111-1111-111111111111';
const ACCOUNT_B = '22222222-2222-2222-2222-222222222222';
const TRANSACTION_ID = '33333333-3333-3333-3333-333333333333';

function renderPayments() {
  return renderAt(<LedgerPaymentsPage />, '/ledger/payments', '/ledger/payments');
}

describe('LedgerPaymentsPage', () => {
  it('states plainly that this is create-only, since there is no GET /payments', () => {
    renderPayments();
    expect(screen.getByText(/create-only/i)).toBeInTheDocument();
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
    renderPayments();

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
