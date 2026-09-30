import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LedgerCreateAccountPage } from './LedgerCreateAccountPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

describe('LedgerCreateAccountPage', () => {
  it('creates an account via POST /accounts and confirms it', async () => {
    server.use(
      http.post('/accounts', () =>
        HttpResponse.json(
          { id: '99999999-9999-9999-9999-999999999999', name: 'New Co', currency: 'USD', createdAt: '2026-09-10T08:00:00Z' },
          { status: 201 },
        ),
      ),
    );
    renderAt(<LedgerCreateAccountPage />, '/ledger/accounts/new', '/ledger/accounts/new');

    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox', { name: /^Name$/i }), 'New Co');
    await user.click(screen.getByRole('button', { name: /create account/i }));

    expect(await screen.findByText(/created new co/i)).toBeInTheDocument();
  });
});
