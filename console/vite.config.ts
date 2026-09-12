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
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/detection': { target: 'http://localhost:8080', changeOrigin: true },
      '/validation': { target: 'http://localhost:8080', changeOrigin: true },
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
