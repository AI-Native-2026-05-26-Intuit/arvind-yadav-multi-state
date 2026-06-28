import { setupServer } from 'msw/node';
import { handlers } from './handlers';

// Lifecycle (listen/resetHandlers/close) lives in setupTests.ts so a single
// setupFiles entry owns the test environment wiring.
export const server = setupServer(...handlers);
