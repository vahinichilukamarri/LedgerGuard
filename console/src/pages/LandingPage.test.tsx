import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { LandingPage } from './LandingPage';
import { renderAt } from '../test/render';

describe('LandingPage', () => {
  it('names the product and every way in leads to the dashboard', () => {
    renderAt(<LandingPage />, '/', '/');

    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getAllByText(/LedgerGuard/).length).toBeGreaterThan(0);

    const entries = screen.getAllByRole('link', { name: /open dashboard/i });
    expect(entries.length).toBeGreaterThanOrEqual(2);
    entries.forEach((link) => expect(link).toHaveAttribute('href', '/overview'));
  });

  it('explains what the system does, section by section, with working in-page anchors', () => {
    const { container } = renderAt(<LandingPage />, '/', '/');

    expect(container.querySelector('#features')).toBeInTheDocument();
    expect(container.querySelector('#how')).toBeInTheDocument();
    expect(container.querySelector('#principles')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Features' })).toHaveAttribute('href', '#features');
    expect(screen.getByText(/no blended score/i)).toBeInTheDocument();
  });

  it('carries no live data of its own and does not render the app shell', () => {
    renderAt(<LandingPage />, '/', '/');
    expect(screen.queryByRole('navigation', { name: 'Sections' })).not.toBeInTheDocument();
  });
});
