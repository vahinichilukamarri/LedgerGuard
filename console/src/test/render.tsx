import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * One render helper, with the settings that keep async tests deterministic.
 *
 * A fresh `QueryClient` per test, so nothing leaks between them, and `retry:
 * false`, so a deliberate failure fails once instead of becoming a timing-
 * dependent sequence. `gcTime: Infinity` keeps a cached response from being
 * collected mid-assertion.
 *
 * No `StrictMode` here. In development StrictMode double-invokes effects on
 * purpose and that is worth having in the app; in a test it doubles the request
 * count for no benefit and makes "was this fetched once" unanswerable.
 */
export function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 0, refetchOnWindowFocus: false },
    },
  });
}

export function Providers({
  children,
  client,
  initialEntries = ['/'],
}: {
  children: ReactNode;
  client?: QueryClient;
  initialEntries?: string[];
}) {
  return (
    <QueryClientProvider client={client ?? makeClient()}>
      <MemoryRouter initialEntries={initialEntries}>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

/** Render a component with no routing needs. */
export function renderPlain(ui: ReactElement) {
  return render(<Providers>{ui}</Providers>);
}

/** Render a page at a URL, with the route pattern it expects to be matched by. */
export function renderAt(ui: ReactElement, path: string, url: string) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path={path} element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
