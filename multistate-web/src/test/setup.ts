import '@testing-library/jest-dom';
import { afterEach, beforeAll } from 'vitest';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';
import { useTenantChatStore } from '../stores/useTenantChatStore';
import './server';

// jsdom installs its own AbortSignal class. MSW's interceptor wraps the
// global fetch and constructs a Node-side Request inside its proxy; that
// constructor does an `instanceof` check against undici's internal
// AbortSignal class and rejects jsdom's instances with:
//
//   "RequestInit: Expected signal (\"AbortSignal {}\") to be an instance
//    of AbortSignal."
//
// useChat() always passes a signal to fetch, so without intervention every
// streaming test would error before MSW can serve the stub. We register a
// beforeAll that runs *after* MSW has installed its proxy and wrap that
// proxy: strip the offending signal and bridge it onto the returned stream
// (closing the cloned reader when the original signal fires).
beforeAll(() => {
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
// calls it from a useEffect whenever messages change, which would throw in
// tests without this stub.
if (typeof Element !== 'undefined' && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}

// jsdom defines localStorage/sessionStorage on Window.prototype as a getter,
// not as own properties of the window instance — so vitest's globalThis-from-
// window copy skips them. After populateGlobal, `window === globalThis`, so
// calling the getter through Window.prototype directly (with globalThis as
// `this`) returns the real Storage objects without infinite recursion.
// Node 22 exposes an experimental `localStorage` getter that returns undefined
// unless `--localstorage-file` is passed, and that getter sits on globalThis
// ahead of jsdom's. Vitest does add jsdom keys to globalThis but localStorage
// isn't in its allow-list. Forward the real jsdom Storage objects from
// `globalThis.jsdom.window` so test code sees a working localStorage.
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

// Reset the Zustand filter store between tests so threshold/search/state
// chips set in one test cannot bleed into the next. The persist middleware
// also writes to localStorage on each set(), so clear that too.
afterEach(() => {
  useTenantFilterStore.getState().reset();
  useTenantChatStore.getState().clear();
  if (typeof localStorage !== 'undefined') {
    localStorage.clear();
  }
});
