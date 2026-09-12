import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import './styles.css';

/**
 * One query client, configured to trust the server's caching rather than
 * duplicate it.
 *
 * Phase 11 already caches narratives server-side, and the detection reads are
 * computed per request from the ledger. A long client `staleTime` would mean the
 * console showing yesterday's composite next to today's ledger, with no way for
 * a reviewer to tell which they were looking at, so responses go stale quickly
 * and the cache exists to deduplicate concurrent requests rather than to store
 * answers.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

const container = document.getElementById('root');
if (!container) {
  throw new Error('No #root element to mount into.');
}

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
