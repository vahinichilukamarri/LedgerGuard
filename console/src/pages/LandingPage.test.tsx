import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { LandingPage } from './LandingPage';
import { renderAt } from '../test/render';

function cardLinkFor(title: string): HTMLElement {
  const heading = screen.getByRole('heading', { name: title, level: 3 });
  const link = heading.closest('a');
  if (!link) {
    throw new Error(`no enclosing <a> for the "${title}" card`);
  }
  return link;
}

describe('LandingPage', () => {
  it('links to the dashboard and every domain, with no live data of its own', () => {
    renderAt(<LandingPage />, '/', '/');

    expect(screen.getByRole('link', { name: /open dashboard/i })).toHaveAttribute('href', '/overview');
    expect(cardLinkFor('Ledger')).toHaveAttribute('href', '/ledger/accounts');
    expect(cardLinkFor('Reconciliation')).toHaveAttribute('href', '/reconciliation');
    expect(cardLinkFor('Detection')).toHaveAttribute('href', '/anomalies');
    expect(cardLinkFor('Validation')).toHaveAttribute('href', '/validation');
    expect(cardLinkFor('Simulation')).toHaveAttribute('href', '/simulation');
  });

  it('walls the simulation card off with the admin treatment, same as everywhere else', () => {
    renderAt(<LandingPage />, '/', '/');
    expect(cardLinkFor('Simulation')).toHaveClass('landing-card-admin');
  });
});
