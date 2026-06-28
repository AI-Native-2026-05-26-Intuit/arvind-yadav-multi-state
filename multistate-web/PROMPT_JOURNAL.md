# Prompt Journal — Week 4 Day 4

Every AI prompt in this PR that produced shipped code, with the model's
relevant output and my verdict.

Model: **Claude Opus 4.7 (1M context)** via Claude Code CLI.

---

## 1. Bootstrap: AI SDK + Hono proxy + first `useChat`

**Prompt (paraphrased):** Inside `multistate-web/`, install `ai`, `@ai-sdk/react`,
`@ai-sdk/openai-compatible`, `zod`, `hono`, and `tsx`. Create `server/index.ts`
(Hono node server) and `server/api/chat.ts` (POST `/chat` calling `streamText`
against `http://localhost:8080/ai`, returning `toDataStreamResponse` with SSE
headers and forwarding `c.req.raw.signal` as `abortSignal`). Proxy `/api` to
`:3001` in `vite.config.ts`. Render a `TenantChatPanel` calling
`useChat({ api: '/api/chat', id: tenant-${id} })` and mount it at
`/tenants/:id/chat` inside the protected layout. Pin against the v3/v4 spec snippets.

**Model output (highlights):**
- Initially installed `ai@^6` and `@ai-sdk/react@^3`; on first typecheck noticed
  v6 renamed `toDataStreamResponse` → `toUIMessageStreamResponse` and dropped
  `input`/`handleInputChange`/`handleSubmit`/`isLoading`/`reload` from `useChat`.
- Downgraded to `ai@^4.3.19` + `@ai-sdk/react@^1.2.12` +
  `@ai-sdk/openai-compatible@^0.2.16` so the reference code typechecked unchanged.
- Added `@hono/node-server` to actually run Hono on Node.
- Added `server/` to `tsconfig.json` include so the proxy is type-checked.

**Verdict:** **Accepted.** Pinning to v4 was the right call — the spec snippets
were verbatim against v4 and the v6 API renames would have forced edits to every
line of the reference. Documented as an explicit deviation in both READMEs.

---

## 2. Streaming UX polish (Stop / Regenerate / scrollIntoView / error pane)

**Prompt (paraphrased):** Extend `TenantChatPanel` with `stop`, `reload`,
`isLoading`, `error`. Render `role="status"` while loading, `role="alert"` on
error. Disable Stop unless loading; disable Regenerate while loading; disable
Send when `input.trim() === ''`. Auto-scroll on each `messages` change via
`useEffect` + ref + `scrollIntoView({ behavior: 'smooth' })`. Map upstream 4xx/5xx
into a sentinel SSE error frame so the client gets a clean error.

**Model output (highlights):**
- Wired the four disabled-button rules exactly per spec, including
  `input.trim() === ''` rather than `input.length === 0`.
- Built a Hono `upstreamErrorMapper` middleware that catches throws from the
  route, emits a stream containing only the AI SDK v4 error frame `3:"<msg>"\n`,
  and closes — `useChat`'s decoder turns this into a clean `Error.message`
  instead of the half-open connection reported as "Failed to parse stream".
- Also wired `getErrorMessage` on `toDataStreamResponse` so mid-stream upstream
  drops surface the real cause rather than the SDK's default "An error occurred".

**Verdict:** **Accepted.** The sentinel-frame pattern is exactly what the v4
data-stream protocol exposes for this purpose; both the protocol value (`3`) and
the framing (`<prefix>:<json-payload>\n`) come straight from the spec.

---

## 3. Streamed tool calls + persisted history

**Prompt (paraphrased):** Create `server/api/chat-tools.ts` with two
zod-typed `tool({ description, parameters, execute })` entries — `lookupTenant`
hits `GET /api/v1/tenants/:id`, `nexusForState` hits `GET /api/v1/tenants?state=…`.
Pass them into `streamText` with `maxSteps: 3` so the assistant can chain
call → result → reply in one HTTP request. Update the system prompt. Create
`ToolCallCard` (aside `aria-label="tool-call"` with name, args, and
`data-testid="tool-result"` when `state === 'result'`) and map
`message.toolInvocations ?? []` inline in the panel. Create `useTenantChatStore`
(persist, `name: 'uc:tenant-chat'`, `messages: Message[]`, `appendAssistantMessage`,
`clear`) and call `appendAssistantMessage` ONLY from `onFinish`.

**Model output (highlights):**
- Suggested "writing partial tokens to the store so the UI updates as it
  streams" in an early scratch draft. **I rejected this.** §9 of the assignment
  flags it as the canonical sticking point: rewriting the entire serialized
  persist blob on every token defeats rehydration, and any mid-stream Stop
  click would leave a half-finished message on disk forever. The shipped code
  writes only from `onFinish`, exactly as the spec requires.
- Seeded `useChat.initialMessages` with a one-shot
  `useTenantChatStore.getState().messages` snapshot at mount — using a live
  selector would reset the hook on every store write and replay the conversation.
- The system prompt explicitly teaches the assistant to "never guess fields like
  state, status, or thresholds" and to follow up with prose after tool results
  rather than dumping JSON.

**Verdict:** **Accepted (with the mid-stream persist suggestion rejected).**
`maxSteps: 3` was the load-bearing setting — without it the assistant stops
after the tool result and the UI renders raw JSON.

---

## 4. MSW SSE handler + four new Vitest files

**Prompt (paraphrased):** Write `src/test/sse-handlers.ts` returning a hand-rolled
`new ReadableStream` of three `0:"<chunk>"` text frames and a `d:{...}` finish
frame, with `X-Vercel-AI-Data-Stream: v1`. Add four test files:
`TenantChatPanel.test.tsx` (streamed reply, Stop mid-stream, Regenerate),
`TenantChatPanel.error.test.tsx` (500 → `role="alert"`),
`ToolCallCard.test.tsx` (three render states),
`useTenantChatStore.test.ts` (persist round-trip). Target ≥ 20 new tests; ≥ 40 total.

**Model output (highlights):**
- First pass hit `"RequestInit: Expected signal (\"AbortSignal {}\") to be an
  instance of AbortSignal"` on every streaming test — MSW's
  `@mswjs/interceptors`'s `FetchRequest` constructor `instanceof`-checks the
  signal against undici's internal class before any handler runs, and jsdom's
  `AbortSignal` is a different class.
- Initial fix attempt (replacing `globalThis.AbortController` with `node:abort_controller`)
  failed — that module doesn't exist; AbortController is a Node global, not a
  separate built-in.
- Second attempt patched `globalThis.fetch` in setup.ts top-level — silently
  clobbered by MSW's own proxy install in `setupServer.listen()`.
- Final fix: register the fetch wrapper in a `beforeAll` that runs **after**
  MSW installs its proxy. The wrapper strips the offending signal, forwards
  to MSW's proxy, and bridges abort onto a cloned `ReadableStream` of the
  response body — so calling `stop()` cancels the bridged reader and `useChat`
  sees end-of-stream, matching what the production server gets from
  `c.req.raw.signal`.
- Persist round-trip test failed initially because the chat store captured
  `window.localStorage` eagerly via `createJSONStorage(() => …)`, and at module
  load time under jsdom that returned `undefined` — falling through to the
  in-memory `Map` fallback. Fixed by handing `createJSONStorage` a permanent
  proxy whose `getItem`/`setItem`/`removeItem` defer the lookup to every call.
- Each test renders with a unique tenant id — `useChat({ id })` is keyed off
  a module-level Chat singleton map, so re-using the same id would leak
  messages between tests.
- Tests shipped: 11 (panel) + 2 (error) + 8 (ToolCallCard, including the
  `data-state` attribute test added at rubric-review time) + 6 (store) = 27 new
  tests, **47 total**.

**Verdict:** **Accepted.** Documented the AbortSignal-bridge and the deferred-
storage-proxy patterns in both READMEs so the next dev doesn't relearn them.

---

## 5. Documentation pass (root + per-app README)

**Prompt (paraphrased):** Update both READMEs to log W4 D4: Hono proxy, useChat
wiring, tool calling, persisted chat store, error mapping, MSW SSE handler,
AbortSignal bridge, test counts.

**Model output (highlights):**
- Root README: new "Week 4 Day 4" block between W4 D3 and `## Build & test`,
  matching the existing block pattern (bold header + one bullet per shipped
  concept + a "Deviation" sub-bullet for the AI SDK v4 pin).
- Per-app README: top blurb names three data clients now (Apollo + TanStack +
  AI SDK), scripts table gains `dev:server`, project-layout tree grows the
  `server/` directory and four new test files, a dedicated "Streaming chat
  panel" section between Optimistic mutation and State architecture, and the
  test-infra section gets a new "AbortSignal bridge for `useChat`" subsection.

**Verdict:** **Accepted.** No edits to existing W4 D1/D2/D3 documentation —
new content is strictly additive.

---

## Reflection — accepted / rejected suggestions

**Accepted:** the model's recommendation to pin the AI SDK at the v4 family
(`ai@^4.3.19`) rather than the v6 release that `pnpm add ai` resolved to. The
v6 API renames (`toDataStreamResponse` → `toUIMessageStreamResponse`; removal
of `input`/`handleSubmit`/`isLoading`/`reload` from `useChat`) would have
forced edits to every line of the reference snippet, and the rubric's pass
signal explicitly names `toDataStreamResponse` and `input.trim() === ''`. The
deviation is documented as an explicit note in both READMEs.

**Rejected:** the model's first-pass suggestion to persist tokens to the
Zustand store as they stream, "so the UI updates as it streams". §9 of the
assignment names this exact shortcut as a sticking-point trap: rewriting the
serialized persist blob on every token defeats the persist middleware's
rehydration (each keystroke triggers a JSON.stringify of the full transcript),
and any Stop mid-stream leaves a permanent half-finished message on disk.
`useChat` already drives the visible transcript from its in-memory state —
the store is only the persistence layer for completed turns. Shipped code
writes from `onFinish` only, exactly as the spec requires; the
useTenantChatStore test for Stop-then-reload asserts the partial does NOT
land in localStorage.

I did **not** see a suggestion to paste an `nginx` config or to drop
`proxy_buffering off;` — the Hono proxy ships the four headers
(`Cache-Control: no-cache, no-transform`, `Connection: keep-alive`,
`X-Accel-Buffering: no`, `Content-Type: text/event-stream`) inline in
`server/api/chat.ts`, which is what nginx (and the Vite dev proxy) actually
read to skip buffering. Documented inline in the file and in both READMEs.

---

# Prompt Journal — Week 4 Day 5

W4 D5 capstone: testing pyramid (component / integration / a11y / e2e)
behind a single `pnpm check` quality gate.

---

## 1. Vitest harness + ≥ 15 component tests

**Prompt (paraphrased):** Install dev deps (`@vitest/coverage-v8`, `jest-axe`,
the RTL stack). Rewrite `vitest.config.ts` to merge with `vite.config.ts`,
set `environment: 'jsdom'`, `setupFiles: ['./src/test/setupTests.ts']`, and a
coverage block with `provider: 'v8'`, `include: ['src/**/*.{ts,tsx}']`,
exclusions for `src/gql/generated/**` and the test folder, and
`thresholds.branches: 70`. Create `setupTests.ts` (jest-dom matcher, extend
expect with `toHaveNoViolations`, MSW lifecycle, per-test `localStorage.clear()`).
Create `renderWithProviders.tsx` (MockedProvider + QueryClientProvider +
MemoryRouter, returns a single `userEvent.setup()` per render). Write ≥ 15
component tests across `TenantListPage.test.tsx` and `TenantSummaryPage.test.tsx`
using `getByRole` / `findByRole` as the primary query.

**Model output (highlights):**
- First pass shipped `vitest.config.ts` with `lines/funcs/statements: 75` as
  *global* thresholds (mirroring the appendix). Coverage failed those gates on
  files outside the W4 D5 scope (`LoginPage`, `router.tsx`, `useTenant`).
- Re-scoped the 75% line/func/statement bar to `src/pages/**` and kept
  `branches: 70` global, since the rubric only names branches as the gate.
- `Assertion<T>` module augmentation for `toHaveNoViolations` initially failed
  with `TS2664: Invalid module name in augmentation, module '@vitest/expect'
  cannot be found.` and `TS2428: All declarations of 'Assertion' must have
  identical type parameters.` Fixed by augmenting the `vitest` re-export with
  `interface Assertion<T = any>` to match the upstream generic default.
- A standalone `jest-axe.d.ts` ambient declaration replaced `@types/jest-axe`
  (which targets jest, not vitest's matcher contract).
- Updated `TenantListPage.tsx` + `TenantSummaryPage.tsx` to expose the
  accessible names the rubric calls for: `role="status"` with `loading…` and
  `no results`, `role="alert"` for errors, a labelled search input wired to
  `useTenantFilterStore`.

**Verdict:** **Accepted with corrections.** The model's per-directory threshold
override is the right shape for a real project: tighter local gates on the
code the new tests target, looser global bar so unrelated untested code (W6
auth, Login) doesn't fail CI before its own tests land. 18 tests shipped
(11 list + 7 summary), well past the ≥ 15 gate. The `Assertion<T = any>`
augmentation is non-obvious — leaving the default off (`T = unknown`) silently
breaks the interface merge with vitest's upstream `Assertion<T = any>`.

---

## 2. MSW integration tests (≥ 12)

**Prompt (paraphrased):** Extend `src/test/handlers.ts` with `tenantHandlers`
(Apollo `LatestTenants` happy + REST `/api/v1/tenants` happy) and a separately-
exported `tenantErrorHandler` so individual tests can `server.use(...)` to opt
into the 500 branch. Write ≥ 12 integration tests covering REST happy / 500,
Apollo loading / happy / cache-hit, and filter-store + REST narrowing.

**Model output (highlights):**
- Suggested wiring the integration tests through `MockedProvider` again —
  rejected (see verdict). Switched to a real `ApolloClient + HttpLink`
  exported as `makeIntegrationApolloClient()` from `renderWithProviders.tsx`
  so requests actually leave Apollo and land on MSW.
- Shipped both `/api/v1/tenants` and `http://localhost:8080/api/v1/tenants`
  handler variants since the real fetch URL depends on whether the page hits
  same-origin or the proxied form.
- `apolloLoadingHandler` returns `new Promise<never>(() => undefined)` —
  needs an explicit `Promise<never>` annotation because msw's resolver type
  unions a `DefaultBodyType` constraint that fails to widen `Promise<unknown>`.
- For GraphQL error mocks, replaced `new GraphQLError('msg')` with plain
  `{ message: 'msg' }` literals — `exactOptionalPropertyTypes: true` rejects
  `GraphQLError`'s optional `locations: SourceLocation[] | undefined` because
  the target type is `readonly SourceLocation[]` (without `| undefined`).
- For network error mocks, replaced `new ApolloError({ errorMessage })` with a
  plain `new Error('Boom')` — `ApolloError` is not a constructor in v4.

**Verdict:** **Accepted.** Pushed back on the "use MockedProvider again"
suggestion because it would have made these tests indistinguishable from the
Task 1 component tests — the rubric explicitly asks for MSW integration
coverage, which means the network path has to actually run. The integration
suite shipped 13 tests (≥ 12 gate). Cache-hit tests use the same `QueryClient`
or `ApolloClient` instance across two renders and then flip the network to
500 — if the cached value is gone, the second render alerts; in the cache hit
path the second render still shows the cell.

---

## 3. Playwright E2E happy-path

**Prompt (paraphrased):** Install Playwright + `@axe-core/playwright`. Add
`playwright.config.ts` (testDir, fullyParallel, retries on CI, chromium
project, webServer booting `pnpm dev`, `use.storageState` →
`e2e/.auth/user.json`). Add `e2e/global-setup.ts` (log in once, persist
storage state). Write `e2e/tenant-chat.spec.ts` — navigate to `/tenants`,
click a row by accessible name, drive the W4 D4 chat panel, assert streamed
tokens in `getByRole('log')`, assert the tool-call card is visible, reload,
assert the conversation persists.

**Model output (highlights):**
- First pass suggested keeping `<ul><li><a>` markup on the tenant list and
  having the test query by `link` accessible name — but the rubric snippet
  expects `page.getByRole('row', { name })`, which only resolves on an actual
  `<table>`. Switched the list page to render a `<table>` (header row + data
  rows) and updated the vitest tests to use `getByRole('cell', { name })`.
- For the chat panel: added `role="log" aria-live="polite"` to the transcript
  `<ul>` so the spec's `page.getByRole('log')` resolves; renamed the input's
  `aria-label` from `chat-message` to `chat-input` so
  `getByRole('textbox', { name: /chat-input/i })` finds the input the user
  types into.
- The model offered a `data-testid="row"` shortcut on the table rows. Rejected
  — the rubric explicitly says `data-testid` only where role / accessible name
  is genuinely insufficient. The `<tr>`'s accessible name is the
  concatenation of its `<td>` text, which `getByRole('row', { name: /Stub
  Tenant 01/i })` already matches.
- LoginPage gained real email + password inputs (was a single-button stub) so
  global-setup's `getByLabel(/email/i)` and `getByLabel(/password/i)` resolve.
- The model initially suggested `await page.waitForTimeout(500)` to "let the
  stream finish." Rejected — Playwright's `expect(...).toContainText(...)`
  retries on its own; the timeout would have made the test flaky and slow.
- Network is stubbed at `page.route('**/graphql', ...)` and
  `page.route('**/api/chat', ...)` since the W3 D4 Spring AI backend isn't
  running in CI. The `/api/chat` stub emits the AI SDK v4 data-stream
  protocol: `0:"<text>"` deltas, a `9:` tool-call frame, an `a:` tool-result
  frame, and a `d:` finish frame.

**Verdict:** **Accepted with two rejected shortcuts** (data-testid on the row,
`waitForTimeout`). The model also initially imported `ApolloProvider` from
`@apollo/client` — fails at runtime as `undefined` because Apollo v4 moved it
to `@apollo/client/react`. Caught on first test run.

---

## 4. a11y + ESLint 9 + CI gate

**Prompt (paraphrased):** Add one `toHaveNoViolations()` assertion per page
test. Wire `AxeBuilder({ page }).withTags(['wcag2a','wcag2aa'])` into the
Playwright spec on the detail-page state — one a11y scan per page state.
Install ESLint 9 + plugins, write a flat config with
`recommendedTypeChecked`, `react-hooks/recommended`, `jsx-a11y/recommended`,
and bans on `@typescript-eslint/no-explicit-any` + `as any` via
`no-restricted-syntax`. Add a `pnpm check` script
(`tsc --noEmit && eslint . && vitest run --coverage && playwright test`) and
update the GitHub Action to call it as the single CI entrypoint. Add a
`tenant.a11y.test.tsx` for tab order + axe scans. Total tests ≥ 30.

**Model output (highlights):**
- First flat-config pass spread `tseslint.configs.recommendedTypeChecked` at
  the top level — failed at lint time on `eslint.config.js` itself with
  `You have used a rule which requires type information, but don't have
  parserOptions set to generate type information for this file.` Fixed by
  mapping the typeChecked configs through `.map(cfg => ({ ...cfg, files:
  ['src/**/*.{ts,tsx}'] }))` so the type-aware rules only apply to source.
- `no-restricted-syntax` needed both selectors: `TSTypeAssertion` catches the
  legacy `<any>foo` syntax, `TSAsExpression` catches the modern `foo as any`.
  `@typescript-eslint/no-explicit-any` alone doesn't cover the cast form.
- ESLint caught two real issues the new rules surface: an `(error as Error)`
  cast in `TenantTable.tsx` (`no-unnecessary-type-assertion`) and an `async`
  arrow with no `await` in `TenantListPage.test.tsx` (`require-await`). Both
  fixed in the same commit.
- The Playwright AxeBuilder scan is placed *after* `getByRole('textbox', {
  name: /chat-input/i }).toBeVisible()` so axe sees a settled DOM, not the
  paint between route change and chat mount.
- CI workflow now installs Playwright browsers
  (`pnpm exec playwright install --with-deps chromium`) before calling
  `pnpm check`, since `pnpm check` runs the e2e step.

**Verdict:** **Accepted.** The scoped-typeChecked pattern is the standard
ESLint 9 flat-config workaround for project-scoped rules; documenting it in
the config file's comment block. The `as any` ban via `no-restricted-syntax`
is belt-and-braces over `no-explicit-any`, but the rubric explicitly names
both — and `no-explicit-any` does not flag the cast form. Final tally: 80
tests (79 vitest + 1 Playwright), branches 86% global / 89% in `src/pages`,
zero ESLint errors, zero axe violations across 4 vitest scans + 1 Playwright
scan, `pnpm check` exits 0.
