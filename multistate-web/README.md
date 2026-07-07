# multistate-web

React 19 + TypeScript + Vite frontend for the UptimeCrew Multi-State project. Renders a tenant list (Apollo + GraphQL), a tenant detail view, a tenant summary mutation with optimistic-response cache write, and a **streaming chat panel** that talks to the W3 D4 Spring AI endpoint through a Hono proxy. Layers a Zustand store (filter state, partially persisted), a Zustand store for completed chat turns (fully persisted), a `useReducer` data state machine, a debounced derived value, and a class-based `ErrorBoundary` with retry on top of the base scaffold. Data fetching is split three ways: Apollo Client for GraphQL (`/graphql`), TanStack Query for REST (`/api/v1/…`), and the Vercel AI SDK for SSE chat streaming (`/api/chat` → Hono on `:3001` → Spring AI on `:8080`).

The Spring Boot backend lives at the repo root; see the [root README](../README.md) for the broader project context and the W4 D1 / D2 / D3 / D4 entries for what this app is for.

## Requirements

- Node.js 22+ (CI runs on `node-version: 22`; pnpm 11 requires ≥ 22.13)
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
| `pnpm run dev:server` | Run the Hono chat proxy on `:3001` via `tsx watch` so the dev Vite proxy has something to forward `/api/chat` to. |
| `pnpm run build` | Type-check (`tsc --noEmit`) then produce a production build in `dist/`. |
| `pnpm run preview` | Serve the built `dist/` locally for a smoke check. |
| `pnpm run lint` | ESLint 9 flat config across the project. |
| `pnpm run typecheck` | TypeScript no-emit pass. |
| `pnpm test` | Run Vitest + Testing Library suite once (non-watch). |
| `pnpm run test:watch` | Watch-mode Vitest. |
| `pnpm run test:coverage` | Vitest run with the v8 coverage reporter (branches gate ≥ 70 globally, ≥ 70 on `src/pages/**`). |
| `pnpm run e2e` | Playwright run (boots `pnpm dev` via the config's `webServer`). |
| `pnpm run check` | Single CI entrypoint: `tsc --noEmit && eslint . && vitest run --coverage && playwright test`. |
| `pnpm run codegen` | Run GraphQL Codegen once against the live `/graphql` schema. Requires the backend to be reachable at `http://localhost:8080/graphql`. |
| `pnpm run codegen:watch` | Same as `codegen`, but re-runs on document changes. |

## Project layout

```
multistate-web/
  codegen.ts                          # GraphQL Codegen config (client preset)
  server/                             # Hono chat proxy (run via tsx watch)
    index.ts                          # @hono/node-server boot on :3001
    api/
      chat.ts                         # POST /chat: streamText -> Spring AI;
                                      # upstreamErrorMapper middleware emits an
                                      # AI-SDK v4 error frame on 4xx/5xx
      chat-tools.ts                   # tool(zod schema, execute) for
                                      # lookupTenant + nexusForState (W3 D2 REST)
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
      TenantChatPanel.tsx             # useChat({ api:'/api/chat' }); transcript +
                                      # Stop/Regenerate/Send; renders ToolCallCards
      ToolCallCard.tsx                # aside aria-label="tool-call"; renders the
                                      # three ToolInvocation states (call/result)
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
      useTenantChatStore.ts           # Zustand + persist (uc:tenant-chat);
                                      # appendAssistantMessage from onFinish only
    types/
      tenant.ts                       # TenantViewModel + LineItem types
    test/
      setup.ts                        # lifts jsdom localStorage; scrollIntoView
                                      # shim; beforeAll wraps fetch to strip the
                                      # AbortSignal MSW/undici rejects; per-test
                                      # store reset + localStorage clear
      server.ts                       # MSW setupServer + Vitest lifecycle hooks
      handlers.ts                     # MSW: Apollo + REST + SSE chat handlers
      sse-handlers.ts                 # POST /api/chat -> Vercel AI data stream:
                                      # three 0:"<chunk>" + d:{finishReason} frames
      TenantListPage.test.tsx         # 3-row render + loading spinner
      TenantSummaryPage.test.tsx      # mutation resolves to server value
      ProtectedLayout.test.tsx        # redirect-on-missing + outlet-on-present
      useGetMultiStateRest.test.tsx   # data resolves; idle when id empty
      TenantDetailPage.test.tsx       # page-level: loading/error/data/threshold
      TenantDetailPage.reducer.test.ts# pure reducer transitions
      useTenantFilterStore.test.ts    # store setters + last-write-wins + reset
      useDebouncedSearch.test.tsx     # fake timers: 300ms lag + cancel-in-flight
      TenantChatPanel.test.tsx        # streaming, Stop mid-stream, Regenerate,
                                      # scrollIntoView, role/disabled states
      TenantChatPanel.error.test.tsx  # 500 -> role="alert"; spinner clears
      ToolCallCard.test.tsx           # three ToolInvocation render states
      useTenantChatStore.test.ts      # append/clear/order + persist round-trip
  eslint.config.js                    # ESLint 9 flat config
  vite.config.ts                      # Vite (React plugin) + /api -> :3001 proxy
  vitest.config.ts                    # Vitest + jsdom environment
  tsconfig.json                       # include: ["src", "server", ...]
```

## Routing

`createBrowserRouter` (react-router 7) defines:

- `/login` — public; writes a dev JWT to `localStorage` and navigates to `/tenants`.
- `/` → `<Navigate to="/tenants" replace />`.
- `<ProtectedLayout>` wraps `/tenants`, `/tenants/:id`, `/tenants/:id/summary`, and `/tenants/:id/chat`. The layout reads `uc:jwt` from `localStorage`; when null it renders `<Navigate to="/login" replace />`, otherwise `<Outlet />`.

**Threat model**: the JWT lives in `localStorage`, which is XSS-readable. Acceptable for the dev loop; the W6 work will move it to an `HttpOnly; SameSite=Strict; Secure` cookie set by the server. See the comment in [`src/apollo/client.ts`](src/apollo/client.ts).

## Data fetching: three clients, one app

- **Apollo Client** owns `/graphql`. `apolloClient` composes `setContext` (reads `uc:jwt` and adds `Authorization: Bearer …` when present) and `HttpLink` (`http://localhost:8080/graphql`). The cache sets `Tenant: { keyFields: ['id'] }` so cache normalisation tracks the server's stable id. Operations live in `src/queries/*.graphql`; GraphQL Codegen produces typed hooks (`useLatestTenantsQuery`, `useSummarizeTenantMutation`) into `src/gql/generated/`.
- **TanStack Query** owns REST. `queryClient` sets `staleTime: 60_000` (one minute of free cache hits between refetches), `refetchOnWindowFocus: false`, `retry: 1`. `useGetMultiStateRest(id)` fetches `/api/v1/tenants/:id` with key `['multistate', id]` and `enabled: Boolean(id)` so an empty id stays idle instead of firing.
- **Vercel AI SDK** (`@ai-sdk/react`'s `useChat`) owns SSE chat streaming. `useChat({ api: '/api/chat' })` POSTs the message history and consumes a `text/event-stream` body framed as `0:"<text chunk>"\n` deltas and a `d:{finishReason, usage}\n` terminator (the AI SDK v4 data-stream protocol). The browser hits its own origin; Vite's dev proxy forwards `/api` to the Hono process on `:3001`. The upstream API key never leaves the proxy.

`<RouterProvider>` mounts inside all three providers so any route — including the protected children — can call any client.

### Optimistic mutation

`TenantSummaryPage` calls `useSummarizeTenantMutation` with an `optimisticResponse` whose `__typename: 'TenantSummary'` is what lets Apollo's cache normalise the placeholder and atomically swap it for the resolved value when the network result lands. The "…thinking…" / `confidence: 'MEDIUM'` payload is the placeholder shape.

Heads up — **Apollo Client v4 changed this behaviour for `useMutation`**: the hook's `data` field is `undefined` while a mutation is in flight, even with an `optimisticResponse` set. The optimistic write still goes to the cache (any `useQuery` reading `TenantSummary` sees it instantly), but it no longer surfaces through `useMutation.data` the way v3 did. The mutation-page test asserts the resolved value, not the placeholder, for that reason — see [`src/test/TenantSummaryPage.test.tsx`](src/test/TenantSummaryPage.test.tsx).

## Streaming chat panel

[`/tenants/:id/chat`](src/pages/TenantChatPanel.tsx) is an SSE-streamed assistant view. The browser holds an open `text/event-stream` connection to a Hono proxy on `:3001`; the proxy holds the upstream API key and calls the W3 D4 Spring AI endpoint at `http://localhost:8080/ai` via the Vercel AI SDK's `streamText`. The assistant can call two tools backed by the W3 D2 REST API. Completed turns persist to localStorage and rehydrate on reload.

### Hono proxy

[`server/index.ts`](server/index.ts) boots Hono via `@hono/node-server` on `:3001`. [`server/api/chat.ts`](server/api/chat.ts) mounts `POST /chat`, builds the `streamText` call against `createOpenAICompatible({ baseURL: 'http://localhost:8080/ai' })`, and returns `result.toDataStreamResponse(...)` with the four headers that keep SSE flowing: `Content-Type: text/event-stream`, `Cache-Control: no-cache, no-transform`, `Connection: keep-alive`, `X-Accel-Buffering: no`. The `no-transform` on `Cache-Control` and the `X-Accel-Buffering: no` are the load-bearing ones — without them nginx (in front of the proxy in any non-dev deployment) buffers the whole response and the browser sees the entire reply land at once instead of token-by-token. `c.req.raw.signal` is forwarded into `streamText` as `abortSignal`, so when the user clicks Stop and the browser aborts the fetch, the upstream LLM call stops too.

A small Hono middleware on `/chat` wraps the route in `try/catch`. On any thrown error — upstream connection refused, Spring AI 4xx/5xx surfaced as `APICallError`, JSON parse failure — it rewrites the response into a stream containing the AI SDK v4 error frame `3:"<message>"\n`. The client decoder turns that into a clean `Error` on `useChat()` instead of the half-open connection the SDK otherwise reports as `"Failed to parse stream"`. For mid-stream failures (upstream drops after some tokens have arrived), `toDataStreamResponse({ getErrorMessage })` surfaces the real cause through the same frame.

### Vite dev proxy

[`vite.config.ts`](vite.config.ts) forwards `server.proxy['/api'] → http://localhost:3001` so the browser's `fetch('/api/chat')` hits Hono. Run both processes:

```bash
pnpm dev:server   # Hono on :3001
pnpm dev          # Vite on :5173
```

### `useChat` + the panel

[`src/pages/TenantChatPanel.tsx`](src/pages/TenantChatPanel.tsx) calls `useChat({ api: '/api/chat', id: \`tenant-${id}\`, initialMessages, onFinish })`. The chat id is per-tenant so two open tabs on different tenants don't share a transcript. `initialMessages` is seeded from a **one-shot** `useTenantChatStore.getState().messages` snapshot at mount — using a live selector would reset `useChat` on every store write and replay the conversation. `onFinish(msg)` calls `appendAssistantMessage(msg)` on the store; that's the only persistence write the panel ever does.

Markup:

- `<ul aria-label="chat-transcript">` with `data-role={m.role}` on every `<li>`, so tests can grab `document.querySelector('li[data-role="assistant"]')`.
- `<form aria-label="chat-input">` wraps the input + Send button. `handleSubmit` and `handleInputChange` come from `useChat`.
- `<p role="status">Assistant is replying...</p>` while `isLoading`; `<p role="alert">Error: …</p>` when the hook's `error` field is set.
- Send is disabled when `input.trim() === ''` or `isLoading`. Stop (`onClick={stop}`) is disabled when not `isLoading`. Regenerate (`onClick={() => reload()}`) is disabled while loading.
- A `useEffect` on `messages` calls `endRef.current?.scrollIntoView({ behavior: 'smooth' })` on a div at the end of the list.

### Tool calling

The assistant has two tools available, defined server-side in [`server/api/chat-tools.ts`](server/api/chat-tools.ts) using the SDK's `tool({ description, parameters, execute })` helper with **zod** parameter schemas:

| Tool | Parameters | Backend |
| --- | --- | --- |
| `lookupTenant` | `{ id: string }` | `GET http://localhost:8080/api/v1/tenants/:id` |
| `nexusForState` | `{ state: string }` | `GET http://localhost:8080/api/v1/tenants?state=…` |

Both throw on non-2xx so the Hono error middleware turns the failure into the SSE error frame.

[`streamText`](server/api/chat.ts) receives `tools: tenantTools` and `maxSteps: 3`. **`maxSteps` is the load-bearing setting** — without it, the assistant stops after the tool result and the user sees raw JSON; with `3`, a tool-call → tool-result → final-reply chain lands in a single HTTP request. The system prompt teaches the assistant when to call each tool ("never guess fields like state, status, or thresholds; quote the returned rows after tool results arrive") and to follow up with a natural-language reply rather than a JSON dump.

[`src/pages/ToolCallCard.tsx`](src/pages/ToolCallCard.tsx) renders each `message.toolInvocations[i]` as `<aside aria-label="tool-call" data-tool-name={…}>` with the tool name, the call args as JSON, and — only when `invocation.state === 'result'` — the result payload under `data-testid="tool-result"`. The panel maps `m.toolInvocations ?? []` beneath the message body so the call appears in-line with the turn that triggered it.

### Persisted history

[`src/stores/useTenantChatStore.ts`](src/stores/useTenantChatStore.ts) is a Zustand store wrapped in `persist` (`name: 'uc:tenant-chat'`). State is `readonly Message[]`; the single mutating action is `appendAssistantMessage(m)`. **It is called only from `useChat`'s `onFinish` callback** — never on every token, never from an `onMessageStream` handler. Writing partial tokens mid-stream would rewrite the entire serialized blob on every keystroke and defeat the persist middleware's rehydration; a Stop click in the middle of a stream would leave a half-finished message on disk forever. Sticking to `onFinish` means a Stop click drops the partial entirely, and reload jumps back to the last completed turn.

The store hands `persist` a permanent storage proxy whose `getItem`/`setItem`/`removeItem` defer the `localStorage` lookup to every call. The eager-capture pattern used by `useTenantFilterStore` is fine in the browser but breaks in jsdom: vitest's setup pass evaluates `window.localStorage` before jsdom's lazy prototype-getter resolves, captures `undefined`, and the persist middleware silently falls through to the in-memory `Map` fallback — making the W4 D4 persist round-trip test impossible to pass without the proxy.

### Deviation — AI SDK major version

The W4 D4 spec snippets use the v3/v4-line API (`streamText().toDataStreamResponse(...)` on the server; `useChat({ api, onFinish, input, handleInputChange, handleSubmit, isLoading, reload })` on the client). The newest `ai` major (v6) renamed the server method to `toUIMessageStreamResponse` and replaced the hook's form helpers with `sendMessage` / `regenerate` / `status`. We pin `ai@^4.3.19` + `@ai-sdk/react@^1.2.12` + `@ai-sdk/openai-compatible@^0.2.16` so the reference code typechecks unchanged.

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

`src/test/setup.ts` does five things per test file: imports `./server` (which calls `setupServer(...handlers)` and registers the Vitest `beforeAll(listen({ onUnhandledRequest: 'error' }))` / `afterEach(resetHandlers)` / `afterAll(close)` hooks), lifts jsdom's `localStorage`/`sessionStorage` onto `globalThis` (vitest's jsdom env doesn't copy them because they're prototype getters; Node 22+ ships an experimental global `localStorage` that returns `undefined` unless `--localstorage-file` is passed, which wins on `globalThis` otherwise), shims `Element.prototype.scrollIntoView` (jsdom doesn't implement it, and `TenantChatPanel` calls it on every `messages` change), registers a `beforeAll` that bridges the AbortSignal mismatch (see below), and runs `useTenantFilterStore.getState().reset()` + `useTenantChatStore.getState().clear()` + `localStorage.clear()` after each test so threshold/chip/chat state doesn't bleed between tests.

The chat store's persist middleware hands `createJSONStorage` a permanent proxy that defers the `localStorage` lookup to every call — see the **Persisted history** section above for why eager capture is wrong under jsdom. The filter store's safe storage factory falls back to an in-memory `Map` when `window.localStorage` is missing — historical belt-and-braces for environments where the lift hasn't run.

### MSW for handlers; MockedProvider for Apollo

[`src/test/handlers.ts`](src/test/handlers.ts) spreads in [`sse-handlers.ts`](src/test/sse-handlers.ts) and registers three more handlers: `graphql.query('LatestTenants', …)` (three stub entities), `graphql.mutation('SummarizeTenant', …)` (stub `TenantSummary`), and `http.get('http://localhost:8080/api/v1/tenants/:id', …)`. The REST test (`useGetMultiStateRest.test.tsx`) exercises MSW directly.

[`sse-handlers.ts`](src/test/sse-handlers.ts) is the chat-stream handler. `http.post('/api/chat', …)` returns a hand-rolled `new ReadableStream({ start(controller) { … } })` that enqueues three `0:"<chunk>"\n` text frames and a `d:{finishReason: "stop", usage}\n` terminator, with `Content-Type: text/event-stream` and `X-Vercel-AI-Data-Stream: v1` so the SDK accepts it as a Vercel-format data stream. Individual tests can override with `server.use(http.post('/api/chat', …))` — e.g. the Stop-mid-stream test installs a handler that emits one token and then waits on `request.signal.aborted`.

The two Apollo-backed component tests use Apollo's official `MockedProvider` instead of going through MSW. Why: jsdom installs its own `AbortSignal` class onto `globalThis`, but Node's intrinsic undici (used by `fetch`, which Apollo's `HttpLink` calls, and which MSW intercepts) rejects that class as `"not an instance of AbortSignal"`. We tried several workarounds (pin Node's intrinsic, walk the prototype chain, pull from `globalThis.jsdom.window`); none worked cleanly for Apollo because jsdom overwrites the intrinsic and there's no public access to undici's internal `AbortSignal`. `MockedProvider` short-circuits the fetch path, which is exactly what Apollo recommends for component tests.

### AbortSignal bridge for `useChat`

The chat tests can't sidestep the fetch path — `useChat` is the unit under test, and we need MSW to feed it a real SSE stream. The same AbortSignal mismatch that broke Apollo bites here: MSW's `@mswjs/interceptors` constructs `new FetchRequest(input, init)` inside its proxy, and that constructor `instanceof`-checks the signal against undici's internal class before any handler runs. Stripping the signal entirely loses the Stop semantics that `useChat` depends on.

The bridge in `setup.ts` is a `beforeAll` that runs **after** MSW installs its proxy. It wraps `globalThis.fetch` (and `window.fetch`): on every call with a `signal` in the `RequestInit`, it strips the signal, forwards the request to the underlying (MSW-wrapped) fetch, and proxies the abort onto a cloned `ReadableStream` of the response body. When the test calls `stop()` and the original signal fires, the cloned reader is cancelled — `useChat` sees end-of-stream and flushes `isLoading: false`, the same behaviour the production server gets from `c.req.raw.signal`.

Each chat test renders with a **unique tenant id** in the URL (`/tenants/stub-${seq}-${Date.now()}/chat`). `useChat({ id })` is keyed off a module-level `Chat` singleton map, so re-using the same id between tests would leak the previous test's messages into the next.

## W4 D5 — testing pyramid & quality gate

W4 D5 closes out the W4 capstone with a four-layer testing pyramid and a single `pnpm check` gate the CI workflow runs as one step.

### Layers

1. **Component tests** — `src/test/TenantListPage.test.tsx`, `src/test/TenantSummaryPage.test.tsx`. Use Apollo's `MockedProvider` via [`src/test/renderWithProviders.tsx`](src/test/renderWithProviders.tsx). Cover heading, loading status (`role="status"` with `"loading…"`), empty state (`role="status"` with `"no results"`), error state (`role="alert"`), and search-input → filter-store wiring. One axe scan per page.
2. **Integration tests** — [`src/pages/TenantSummaryPage.integration.test.tsx`](src/pages/TenantSummaryPage.integration.test.tsx). Drives the page through the real Apollo `HttpLink` (via `makeIntegrationApolloClient`) and the real TanStack Query path so MSW actually answers the request. Covers REST happy / 500, Apollo loading / happy / cache-hit / error, filter narrowing on both data layers, and cross-component filter-store wiring.
3. **A11y tests** — [`src/test/tenant.a11y.test.tsx`](src/test/tenant.a11y.test.tsx). Tab order plus dedicated axe scans on the REST table and the empty state.
4. **End-to-end** — [`e2e/tenant-chat.spec.ts`](e2e/tenant-chat.spec.ts). One Playwright happy-path: login → tenant table row click → chat panel → token stream into `role="log"` → tool-call card inline → reload preserves the assistant message. One `AxeBuilder({ page }).withTags(['wcag2a','wcag2aa']).analyze()` scan on the detail/chat state.

### Helpers

- [`src/test/renderWithProviders.tsx`](src/test/renderWithProviders.tsx) wraps a render in `MockedProvider` (component mode) or `ApolloProvider + HttpLink` (integration mode), a per-render `QueryClient` with `retry: false` / `gcTime: 0`, a `MemoryRouter`, and a single `userEvent.setup()` exposed as `{ user }`.
- [`src/test/handlers.ts`](src/test/handlers.ts) ships `tenantHandlers`, `tenantErrorHandler` (REST 500), `apolloLoadingHandler` (never-settling promise), and `apolloErrorHandler` (GraphQL errors envelope). Tests opt in with `server.use(...)`.

### Auth + global setup

[`e2e/global-setup.ts`](e2e/global-setup.ts) signs in once (email + password → `Sign in`), waits for `/tenants`, and snapshots `page.context().storageState()` to `e2e/.auth/user.json` (gitignored). Every spec inherits `use.storageState` from [`playwright.config.ts`](playwright.config.ts) so no test pays the login cost again.

### Quality gates

- **`pnpm check`** runs `tsc --noEmit && eslint . && vitest run --coverage && playwright test` and is the single entrypoint CI calls.
- **Coverage**: branches ≥ 70 globally and on `src/pages/**`; lines/funcs/statements ≥ 75 inside `src/pages/**`. The W4 capstone load-bearing metric is branches.
- **ESLint 9 flat config** ([`eslint.config.js`](eslint.config.js)): `js.configs.recommended`, `tseslint.configs.recommendedTypeChecked` (scoped to `src/**` so it doesn't try to type-check the config file itself), and the `react-hooks` + `jsx-a11y` recommended rule sets. `@typescript-eslint/no-explicit-any` is `error`; `no-restricted-syntax` rejects both `<any>foo` (`TSTypeAssertion`) and `foo as any` (`TSAsExpression`).
- **A11y**: `expect(results).toHaveNoViolations()` (jest-axe) on the list, summary, REST table, and empty state; Playwright's `AxeBuilder` runs once against the chat state with WCAG 2.1 A + AA.

## CI

[`/.github/workflows/web-ci.yml`](../.github/workflows/web-ci.yml) runs on PRs that touch `multistate-web/**`: `pnpm install --frozen-lockfile` → install Playwright browsers (`pnpm exec playwright install --with-deps chromium`) → **`pnpm check`** → `pnpm run build`. The pre-W4-D5 `lint`/`typecheck`/`test` triple is now subsumed by `pnpm check`. All GitHub Actions in this repo are SHA-pinned (no floating `@v4` tags); see [`.github/dependabot.yml`](../.github/dependabot.yml) for weekly grouped bumps.

The backend delivery pipeline (`multistate-ci`, ECR push, prod promotion) lives in the repo root — see [`.github/PIPELINE.md`](../.github/PIPELINE.md).
