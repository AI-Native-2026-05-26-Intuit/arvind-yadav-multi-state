# multistate-web

React 19 + TypeScript + Vite frontend for the UptimeCrew Multi-State project. Renders a tenant list (Apollo + GraphQL) and a tenant detail view, with a tenant summary mutation that uses an optimistic-response cache write. Layers a Zustand store (filter state, partially persisted), a `useReducer` data state machine, a debounced derived value, and a class-based `ErrorBoundary` with retry on top of the base scaffold. Data fetching is split: Apollo Client for GraphQL (`/graphql`) and TanStack Query for REST (`/api/v1/...`).

The Spring Boot backend lives at the repo root; see the [root README](../README.md) for the broader project context and the W4 D1 / D2 / D3 entries for what this app is for.

## Requirements

- Node.js 20+ (CI runs on `node-version: 20`)
- pnpm 11+ (uses the checked-in `pnpm-lock.yaml`; `pnpm-workspace.yaml` whitelists the `esbuild` and `msw` postinstall scripts under pnpm 11's `allowBuilds` gate)

## Install

From this directory:

```bash
pnpm install --frozen-lockfile
```

## Scripts

| Command | What it does |
| --- | --- |
| `pnpm run dev` | Start the Vite dev server with HMR (default: `http://localhost:5173`). |
| `pnpm run build` | Type-check (`tsc --noEmit`) then produce a production build in `dist/`. |
| `pnpm run preview` | Serve the built `dist/` locally for a smoke check. |
| `pnpm run lint` | ESLint 9 flat config across the project. |
| `pnpm run typecheck` | TypeScript no-emit pass. |
| `pnpm test` | Run Vitest + Testing Library suite once (non-watch). |
| `pnpm run codegen` | Run GraphQL Codegen once against the live `/graphql` schema. Requires the backend to be reachable at `http://localhost:8080/graphql`. |
| `pnpm run codegen:watch` | Same as `codegen`, but re-runs on document changes. |

## Project layout

```
multistate-web/
  codegen.ts                          # GraphQL Codegen config (client preset)
  src/
    main.tsx                          # React 19 root; wraps RouterProvider in
                                      # ApolloProvider + QueryClientProvider
    router.tsx                        # createBrowserRouter; protected children
    queryClient.ts                    # TanStack Query client (staleTime 60s)
    apollo/
      client.ts                       # ApolloClient: HttpLink + setContext auth
                                      # (uc:jwt bearer); Tenant keyFields: ['id']
    queries/
      LatestTenants.graphql           # query LatestTenants
      SummarizeTenant.graphql         # mutation SummarizeTenant($id)
    gql/generated/                    # codegen output (typed hooks)
    pages/
      LoginPage.tsx                   # writes a fake JWT and navigates to /tenants
      TenantListPage.tsx              # useLatestTenantsQuery; loading/error/data
      TenantSummaryPage.tsx           # useSummarizeTenantMutation w/ optimisticResponse
      TenantDetailPage.tsx            # useReducer + dispatching useEffect
      TenantDetailPage.reducer.ts     # DetailState union + detailReducer
    components/
      ProtectedLayout.tsx             # reads uc:jwt; <Navigate> when null, else <Outlet>
      ThresholdSlider.tsx             # subscribes to the store directly
      ThresholdReadout.tsx            # subscribes to the store directly
      FilterStrip.tsx                 # one control per filter field, each its own slice
      ErrorBoundary.tsx               # class component; render-fn fallback
    hooks/
      useGetMultiStateRest.ts         # TanStack useQuery against /api/v1/tenants/:id
      useDebouncedSearch.ts           # reads store.searchText, lagged by delayMs
    stores/
      useTenantFilterStore.ts         # Zustand + devtools + persist (only threshold)
    types/
      tenant.ts                       # TenantViewModel + LineItem types
    test/
      setup.ts                        # lifts jsdom localStorage onto globalThis;
                                      # store reset + localStorage clear per test
      server.ts                       # MSW setupServer + Vitest lifecycle hooks
      handlers.ts                     # MSW: LatestTenants, SummarizeTenant, REST get
      TenantListPage.test.tsx         # 3-row render + loading spinner
      TenantSummaryPage.test.tsx      # mutation resolves to server value
      ProtectedLayout.test.tsx        # redirect-on-missing + outlet-on-present
      useGetMultiStateRest.test.tsx   # data resolves; idle when id empty
      TenantDetailPage.test.tsx       # page-level: loading/error/data/threshold
      TenantDetailPage.reducer.test.ts# pure reducer transitions
      useTenantFilterStore.test.ts    # store setters + last-write-wins + reset
      useDebouncedSearch.test.tsx     # fake timers: 300ms lag + cancel-in-flight
  eslint.config.js                    # ESLint 9 flat config
  vite.config.ts                      # Vite (React plugin)
  vitest.config.ts                    # Vitest + jsdom environment
  tsconfig.json
```

## Routing

`createBrowserRouter` (react-router 7) defines:

- `/login` — public; writes a dev JWT to `localStorage` and navigates to `/tenants`.
- `/` → `<Navigate to="/tenants" replace />`.
- `<ProtectedLayout>` wraps `/tenants`, `/tenants/:id`, `/tenants/:id/summary`. The layout reads `uc:jwt` from `localStorage`; when null it renders `<Navigate to="/login" replace />`, otherwise `<Outlet />`.

**Threat model**: the JWT lives in `localStorage`, which is XSS-readable. Acceptable for the dev loop; the W6 work will move it to an `HttpOnly; SameSite=Strict; Secure` cookie set by the server. See the comment in [`src/apollo/client.ts`](src/apollo/client.ts).

## Data fetching: two clients, one app

- **Apollo Client** owns `/graphql`. `apolloClient` composes `setContext` (reads `uc:jwt` and adds `Authorization: Bearer …` when present) and `HttpLink` (`http://localhost:8080/graphql`). The cache sets `Tenant: { keyFields: ['id'] }` so cache normalisation tracks the server's stable id. Operations live in `src/queries/*.graphql`; GraphQL Codegen produces typed hooks (`useLatestTenantsQuery`, `useSummarizeTenantMutation`) into `src/gql/generated/`.
- **TanStack Query** owns REST. `queryClient` sets `staleTime: 60_000` (one minute of free cache hits between refetches), `refetchOnWindowFocus: false`, `retry: 1`. `useGetMultiStateRest(id)` fetches `/api/v1/tenants/:id` with key `['multistate', id]` and `enabled: Boolean(id)` so an empty id stays idle instead of firing.

`<RouterProvider>` mounts inside both providers so any route — including the protected children — can call either client.

### Optimistic mutation

`TenantSummaryPage` calls `useSummarizeTenantMutation` with an `optimisticResponse` whose `__typename: 'TenantSummary'` is what lets Apollo's cache normalise the placeholder and atomically swap it for the resolved value when the network result lands. The "…thinking…" / `confidence: 'MEDIUM'` payload is the placeholder shape.

Heads up — **Apollo Client v4 changed this behaviour for `useMutation`**: the hook's `data` field is `undefined` while a mutation is in flight, even with an `optimisticResponse` set. The optimistic write still goes to the cache (any `useQuery` reading `TenantSummary` sees it instantly), but it no longer surfaces through `useMutation.data` the way v3 did. The mutation-page test asserts the resolved value, not the placeholder, for that reason — see [`src/test/TenantSummaryPage.test.tsx`](src/test/TenantSummaryPage.test.tsx).

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

`src/test/setup.ts` does three things per test file: imports `./server` (which calls `setupServer(...handlers)` and registers the Vitest `beforeAll(listen({ onUnhandledRequest: 'error' }))` / `afterEach(resetHandlers)` / `afterAll(close)` hooks), lifts jsdom's `localStorage`/`sessionStorage` onto `globalThis` (vitest's jsdom env doesn't copy them because they're prototype getters; Node 22+ ships an experimental global `localStorage` that returns `undefined` unless `--localstorage-file` is passed, which wins on `globalThis` otherwise), and runs `useTenantFilterStore.getState().reset()` + `localStorage.clear()` after each test so threshold/search/chip state doesn't bleed between tests.

The store's persist middleware uses a safe storage factory that falls back to an in-memory `Map` when `window.localStorage` is missing — historical belt-and-braces for environments where the lift above hasn't run.

### MSW for handlers; MockedProvider for Apollo

[`src/test/handlers.ts`](src/test/handlers.ts) registers three handlers: `graphql.query('LatestTenants', …)` (three stub entities), `graphql.mutation('SummarizeTenant', …)` (stub `TenantSummary`), and `http.get('http://localhost:8080/api/v1/tenants/:id', …)`. The REST test (`useGetMultiStateRest.test.tsx`) exercises MSW directly.

The two Apollo-backed component tests use Apollo's official `MockedProvider` instead of going through MSW. Why: jsdom installs its own `AbortSignal` class onto `globalThis`, but Node's intrinsic undici (used by `fetch`, which Apollo's `HttpLink` calls, and which MSW intercepts) rejects that class as `"not an instance of AbortSignal"`. We tried several workarounds (pin Node's intrinsic, walk the prototype chain, pull from `globalThis.jsdom.window`); none work cleanly because jsdom overwrites the intrinsic and there's no public access to undici's internal `AbortSignal`. `MockedProvider` short-circuits the fetch path, which is exactly what Apollo recommends for component tests.

## CI

[`/.github/workflows/web-ci.yml`](../.github/workflows/web-ci.yml) runs on PRs that touch `multistate-web/**`: `pnpm install --frozen-lockfile` → `lint` → `typecheck` → `test` → `build`. Keep the local script names in sync with the workflow.
