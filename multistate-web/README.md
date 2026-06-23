# multistate-web

React 19 + TypeScript + Vite frontend for the UptimeCrew Multi-State project. Renders a tenant detail view backed (for now) by a stub fetch hook. Layers a Zustand store (filter state, partially persisted), a `useReducer` data state machine, a debounced derived value, and a class-based `ErrorBoundary` with retry on top of the base scaffold.

The Spring Boot backend lives at the repo root; see the [root README](../README.md) for the broader project context and the W4 D1 + D2 entries for what this app is for.

## Requirements

- Node.js 20+ (CI runs on `node-version: 20`)
- npm (uses the checked-in `package-lock.json`)

## Install

From this directory:

```bash
npm ci
```

## Scripts

| Command | What it does |
| --- | --- |
| `npm run dev` | Start the Vite dev server with HMR (default: `http://localhost:5173`). |
| `npm run build` | Type-check (`tsc --noEmit`) then produce a production build in `dist/`. |
| `npm run preview` | Serve the built `dist/` locally for a smoke check. |
| `npm run lint` | ESLint 9 flat config across the project. |
| `npm run typecheck` | TypeScript no-emit pass. |
| `npm test` | Run Vitest + Testing Library suite once (non-watch). |

## Project layout

```
multistate-web/
  src/
    main.tsx                            # React 19 root, mounts <App />
    App.tsx                             # hash router; wraps the page in <ErrorBoundary>
    pages/
      TenantDetailPage.tsx              # useReducer + dispatching useEffect; dev-only "Trigger error"
      TenantDetailPage.reducer.ts       # DetailState union + detailReducer (+ exhaustiveness check)
    components/
      ThresholdSlider.tsx               # subscribes to the store directly
      ThresholdReadout.tsx              # subscribes to the store directly
      FilterStrip.tsx                   # one control per filter field, each its own slice
      ErrorBoundary.tsx                 # class component; render-fn fallback; reset re-mounts subtree
    hooks/
      useTenant.ts                      # stub fetch hook (kept for the W4 D3 Apollo swap)
      useDebouncedSearch.ts             # reads store.searchText, returns a value lagged by delayMs
    stores/
      useTenantFilterStore.ts           # Zustand + devtools + persist (partialize keeps only threshold)
    types/
      tenant.ts                         # TenantViewModel + LineItem types
    test/
      setup.ts                          # resets the store + clears localStorage after each test
      TenantDetailPage.test.tsx         # page-level: loading/error/data/threshold
      TenantDetailPage.reducer.test.ts  # pure reducer transitions
      useTenantFilterStore.test.ts      # store setters + last-write-wins + reset
      useDebouncedSearch.test.tsx       # fake timers: 300ms lag + cancel-in-flight
  eslint.config.js                      # ESLint 9 flat config
  vite.config.ts                        # Vite (React plugin)
  vitest.config.ts                      # Vitest + jsdom environment
  tsconfig.json
```

## State architecture

Three layers, each with a different lifetime and scope:

1. **`useTenantFilterStore` (Zustand)** — cross-cutting filter state shared across the page and `FilterStrip`. Five fields: `stateFilter` (chip list), `dateRange` (tuple), `searchText`, `includeArchived`, `threshold`. Each set goes through a named action (`filters/setSearchText`, etc.) so the Redux DevTools timeline is readable. `partialize: (s) => ({ threshold: s.threshold })` — **only threshold persists across reloads**. Persisting `searchText` would be a UX bug (return next week, see stale results filtered by a forgotten "foo"); chips and date range are session state.
2. **`useReducer(detailReducer, INITIAL_DETAIL_STATE)` on the page** — the data state machine for the tenant fetch. `DetailState` is a discriminated union (`idle | loading | success | error | empty`); the page's render tree branches on `state.status` and TypeScript narrows each case. The reducer's `default` branch carries a `const _exhaustive: never = action; return _exhaustive;` — adding a fifth action variant without a matching case fails to compile.
3. **`useDebouncedSearch(delayMs = 300)`** — derives a debounced value from `store.searchText`. The `useEffect` returns `clearTimeout(t)` so a fast typist sees only the final value land (the in-flight timer is cancelled on every keystroke).

### Child components don't take value/onChange

Both `ThresholdSlider` and `ThresholdReadout` subscribe to the store directly (`s.threshold` + `s.setThreshold` for the slider, `s.threshold` for the readout). Adding a third consumer is one selector call — the page never has to know about it. `FilterStrip` follows the same pattern with one slice subscription per inner control, so an unrelated state change (a search keystroke) doesn't re-render the chip row.

## Error handling

[`ErrorBoundary`](src/components/ErrorBoundary.tsx) is the only class component in the project — React error boundaries can't be functional. The fallback is a render function `(error, reset) => ReactNode`; on retry, the boundary wraps its children in `<div key={resetKey}>{children}</div>` and bumps `resetKey`, which forces a fresh mount of the entire subtree. That's the move that makes retrying a transiently-bad component actually work (a `setState({error: null})` alone would replay the same broken render). `componentDidCatch` logs `[ErrorBoundary] caught error` with the component stack.

A dev-only `Trigger error` button on the page is gated by `import.meta.env.DEV`; the click sets a state flag, the next render reads it and `throw new Error(...)`. Throwing **in render** (not in the click handler) is what lets the boundary catch it — React boundaries don't intercept event-handler throws.

## Test infra

`src/test/setup.ts` calls `useTenantFilterStore.getState().reset()` and `localStorage.clear()` after each test so threshold/search/chip state doesn't bleed between tests. The Zustand `persist` middleware uses a safe storage factory that falls back to an in-memory `Map` when `window.localStorage` is missing — Node's experimental jsdom doesn't expose `localStorage` without the `--localstorage-file` flag, and without the fallback the persist middleware would crash on every `set()` in tests.

The store tests use Zustand 5's `setState(getInitialState(), true)` in `beforeEach` — a stronger reset than calling `store.reset()` because it also wipes any non-state fields and bypasses `partialize`.

## CI

[`/.github/workflows/web-ci.yml`](../.github/workflows/web-ci.yml) runs on PRs that touch `multistate-web/**`: `npm ci` → `lint` → `typecheck` → `test` → `build`. Keep the local script names in sync with the workflow.
