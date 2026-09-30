import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { LandingPage } from './LandingPage';
import { renderAt } from '../test/render';

describe('LandingPage', () => {
  it('names the product and has exactly one way in: the dashboard', () => {
    renderAt(<LandingPage />, '/', '/');

    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getAllByText(/LedgerGuard/).length).toBeGreaterThan(0);

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', '/overview');
    expect(links[0]).toHaveTextContent(/open dashboard/i);
  });

  it('carries no live data of its own and does not render the app shell', () => {
    renderAt(<LandingPage />, '/', '/');
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
