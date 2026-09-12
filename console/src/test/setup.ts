import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { cleanup } from '@testing-library/react';
import { server } from './server';

/**
 * `onUnhandledRequest: 'error'` is the important line.
 *
 * The default lets an undeclared request through to the real network, where it
 * fails slowly and turns into a timeout somewhere unrelated. Failing loudly at
 * the request means a component that starts fetching something the test never
 * declared is caught where it happens.
 */
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));

afterEach(() => {
  cleanup();
  server.resetHandlers();
});

afterAll(() => server.close());
