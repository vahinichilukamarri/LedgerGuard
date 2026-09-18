import type { IncomingMessage } from 'node:http';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * The dev server proxies the API rather than the backend enabling CORS.
 *
 * Phase 14 is a frontend-only phase, and the Spring application configures no
 * CORS mapping. Rather than change the backend to accommodate a console, the
 * console makes itself same-origin: every request goes to a relative path and
 * Vite forwards it. Deploying this for real means serving the built assets from
 * the same origin as the API, or adding a CORS mapping then — a deployment
 * decision, deliberately not made here.
 *
 * <h2>Why every entry needs `bypass`</h2>
 *
 * A proxy key matches by prefix, and several SPA routes now share a prefix
 * with a backend path: `/reconciliation` is both the reconciliation page and
 * every `/reconciliation/...` API call; `/validation` is both the validation
 * page and its API; `/accounts/:accountId` (the existing account detail page)
 * shares `/accounts` with the new accounts list and balance endpoints. Without
 * `bypass`, a direct page load or refresh on any of those URLs gets forwarded
 * to Spring instead of serving the SPA, and Spring 404s because it has no
 * route for the bare page path.
 *
 * `bypass` tells Vite to serve the SPA's own files instead of proxying
 * whenever the request looks like a browser navigation rather than an API
 * call — which every `fetch` in `api/client.ts` marks by sending
 * `Accept: application/json`, while a real page load sends `Accept:
 * text/html,...`. Returning the request's own URL from `bypass` is the
 * documented way to opt a request out of proxying.
 */
function bypassBrowserNavigation(req: IncomingMessage) {
  if (req.headers.accept?.includes('text/html')) {
    return req.url;
  }
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/detection': { target: 'http://localhost:8080', changeOrigin: true, bypass: bypassBrowserNavigation },
      '/validation': { target: 'http://localhost:8080', changeOrigin: true, bypass: bypassBrowserNavigation },
      '/accounts': { target: 'http://localhost:8080', changeOrigin: true, bypass: bypassBrowserNavigation },
      '/payments': { target: 'http://localhost:8080', changeOrigin: true, bypass: bypassBrowserNavigation },
      '/transactions': { target: 'http://localhost:8080', changeOrigin: true, bypass: bypassBrowserNavigation },
      '/reconciliation': { target: 'http://localhost:8080', changeOrigin: true, bypass: bypassBrowserNavigation },
      '/admin': { target: 'http://localhost:8080', changeOrigin: true, bypass: bypassBrowserNavigation },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
