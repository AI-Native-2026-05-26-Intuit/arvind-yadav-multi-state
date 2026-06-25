import { Hono } from 'hono';
import type { MiddlewareHandler } from 'hono';
import { streamText } from 'ai';
import { createOpenAICompatible } from '@ai-sdk/openai-compatible';
import { tenantTools } from './chat-tools';

// THREAT MODEL: this proxy holds the upstream API key. The browser never
// sees it. The proxy receives only message history from the authenticated
// W4 D3 protected layout, NOT raw secrets, and only emits text/event-stream
// chunks back. See §9 for the buffering trap.
const upstream = createOpenAICompatible({
  name: 'spring-ai',
  baseURL: 'http://localhost:8080/ai',
});

const SSE_HEADERS = {
  'Content-Type': 'text/event-stream',
  'Cache-Control': 'no-cache, no-transform',
  'Connection': 'keep-alive',
  'X-Accel-Buffering': 'no',
};

// AI SDK v4 data-stream protocol: `3:"<json string>"\n` is the error
// frame the client decoder recognizes. Emitting a stream that contains
// only this frame surfaces a clean Error on useChat() instead of a torn
// connection that the hook reports as an opaque "network error".
function sseErrorResponse(message: string): Response {
  const frame = `3:${JSON.stringify(message)}\n`;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frame));
      controller.close();
    },
  });
  return new Response(stream, { status: 200, headers: SSE_HEADERS });
}

// Maps any thrown error from the downstream handler — including upstream
// Spring AI 4xx/5xx surfaced as APICallError — into a sentinel SSE error
// frame. Without this the browser sees a half-open connection and useChat
// surfaces a confusing "Failed to parse stream" rather than the real cause.
const upstreamErrorMapper: MiddlewareHandler = async (c, next) => {
  try {
    await next();
  } catch (err) {
    const message =
      err instanceof Error ? err.message : 'upstream chat service failed';
    // eslint-disable-next-line no-console
    console.error('[chat] upstream error:', message);
    c.res = sseErrorResponse(message);
  }
};

export const chat = new Hono()
  .use('/chat', upstreamErrorMapper)
  .post('/chat', async (c) => {
    const { messages } = await c.req.json();

    const result = streamText({
      model: upstream.chatModel('uptime-crew-assistant'),
      system:
        'You are an assistant that helps engineers track multi-state tax compliance. ' +
        'When asked about a specific tenant, call lookupTenant with that tenant id ' +
        'before answering — never guess fields like state, status, or thresholds. ' +
        'When asked whether a tenant has nexus in a specific state, call ' +
        'nexusForState with the two-letter state code and quote the returned rows. ' +
        'After tool results arrive, write a short natural-language reply for the ' +
        'engineer — do not just dump JSON.',
      messages,
      tools: tenantTools,
      // Allow tool-call → tool-result → final-reply to land in one HTTP
      // request. Without this, the assistant stops after the tool result
      // and the user sees raw JSON with no follow-up sentence.
      maxSteps: 3,
      abortSignal: c.req.raw.signal,
    });

    return result.toDataStreamResponse({
      headers: SSE_HEADERS,
      // Mid-stream errors (upstream drops the connection after some tokens
      // have already arrived) still need their real message surfaced — the
      // SDK default obscures it as "An error occurred".
      getErrorMessage: (err) =>
        err instanceof Error ? err.message : 'upstream chat service failed',
    });
  });
