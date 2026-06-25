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
