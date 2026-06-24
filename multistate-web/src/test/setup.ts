import '@testing-library/jest-dom';
import { afterEach } from 'vitest';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';
import './server';

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
  if (typeof localStorage !== 'undefined') {
    localStorage.clear();
  }
});
