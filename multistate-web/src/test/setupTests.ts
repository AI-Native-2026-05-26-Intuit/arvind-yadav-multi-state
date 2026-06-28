import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll, expect } from 'vitest';
import { cleanup } from '@testing-library/react';
import { toHaveNoViolations } from 'jest-axe';
import { server } from './server';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';
import { useTenantChatStore } from '../stores/useTenantChatStore';

expect.extend(toHaveNoViolations);

// Re-exported from '@vitest/expect'; matching the original generic default is
// required for the interface-merge to type-check.
declare module 'vitest' {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  interface Assertion<T = any> {
    toHaveNoViolations(): T;
  }
  interface AsymmetricMatchersContaining {
    toHaveNoViolations(): unknown;
  }
}

// jsdom installs its own AbortSignal class. MSW's interceptor wraps the
// global fetch and constructs a Node-side Request inside its proxy; that
// constructor does an `instanceof` check against undici's internal
// AbortSignal class and rejects jsdom's instances. useChat() always passes
// a signal to fetch, so without this wrap streaming tests would error
// before MSW can serve the stub.
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
  const mswFetch = globalThis.fetch.bind(globalThis);
  const wrappedFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    if (!init?.signal) return mswFetch(input, init);
    const { signal, ...rest } = init;
    const response = await mswFetch(input, rest);
    if (!response.body) return response;
    const reader = response.body.getReader();
    const bridged = new ReadableStream<Uint8Array>({
      async start(controller) {
        const onAbort = () => {
          try { controller.close(); } catch { /* already closed */ }
          reader.cancel().catch(() => undefined);
        };
        if (signal.aborted) { onAbort(); return; }
        signal.addEventListener('abort', onAbort, { once: true });
        try {
          while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            controller.enqueue(value);
          }
          controller.close();
        } catch (err) {
          controller.error(err);
        }
      },
    });
    return new Response(bridged, {
      headers: response.headers,
      status:  response.status,
    });
  }) as typeof fetch;
  globalThis.fetch = wrappedFetch;
  if (typeof window !== 'undefined') {
    (window as unknown as { fetch: typeof fetch }).fetch = wrappedFetch;
  }
});

// jsdom does not implement Element.prototype.scrollIntoView — TenantChatPanel
// calls it from a useEffect whenever messages change.
if (typeof Element !== 'undefined' && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}

// Forward jsdom's localStorage/sessionStorage onto globalThis so test code
// sees a working storage on Node 22 + vitest 4 + jsdom 25.
{
  const jsdomWin = (globalThis as { jsdom?: { window: Window } }).jsdom?.window;
  if (jsdomWin) {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: jsdomWin.localStorage,
    });
    Object.defineProperty(globalThis, 'sessionStorage', {
      configurable: true,
      value: jsdomWin.sessionStorage,
    });
  }
}

afterEach(() => {
  cleanup();
  server.resetHandlers();
  useTenantFilterStore.getState().reset();
  useTenantChatStore.getState().clear();
  if (typeof window !== 'undefined') {
    window.localStorage.clear();
  }
  if (typeof localStorage !== 'undefined') {
    localStorage.clear();
  }
});

afterAll(() => server.close());
