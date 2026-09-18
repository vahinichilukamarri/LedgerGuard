import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SimulationPage } from './SimulationPage';
import { renderAt } from '../test/render';
import { HttpResponse, http, server } from '../test/server';

function renderSimulation() {
  server.use(
    http.get('/admin/settlement/records', () => HttpResponse.json([])),
    http.get('/admin/disputes', () => HttpResponse.json([])),
  );
  return renderAt(<SimulationPage />, '/simulation', '/simulation');
}

describe('SimulationPage', () => {
  it('is visually marked as an admin surface, distinct from the real ledger', async () => {
    renderSimulation();
    expect(await screen.findByText(/admin \/ chaos surface/i)).toBeInTheDocument();
  });

  it('requires an admin-toned confirm dialog before injecting a settlement fault', async () => {
    server.use(
      http.post('/admin/settlement/faults', () =>
        HttpResponse.json({ fault: 'DROP_SETTLEMENT', outcome: 'dropped' }),
      ),
    );
    renderSimulation();
    await screen.findByText(/admin \/ chaos surface/i);

    const user = userEvent.setup();
    // Both the fault form and the dispute form below it have a "Transaction
    // id" field — the fault form's is the first one on the page.
    await user.type(screen.getAllByLabelText(/transaction id/i)[0]!, '77777777-7777-7777-7777-777777777777');
    await user.click(screen.getByRole('button', { name: /inject fault…/i }));

    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent(/does not touch the real ledger/i);

    await user.click(screen.getByRole('button', { name: /^inject fault$/i }));
    await waitFor(() => expect(screen.getByText(/dropped/)).toBeInTheDocument());
  });

  it('names FRAUDULENT as the only label-bearing dispute reason', async () => {
    renderSimulation();
    expect(await screen.findByText(/the only label-bearing reason/i)).toBeInTheDocument();
  });
});
