import { Hono } from 'hono';
import { streamText } from 'ai';
import { createOpenAICompatible } from '@ai-sdk/openai-compatible';

// THREAT MODEL: this proxy holds the upstream API key. The browser never
// sees it. The proxy receives only message history from the authenticated
// W4 D3 protected layout, NOT raw secrets, and only emits text/event-stream
// chunks back. See §9 for the buffering trap.
const upstream = createOpenAICompatible({
  name: 'spring-ai',
  baseURL: 'http://localhost:8080/ai',
});

export const chat = new Hono().post('/chat', async (c) => {
  const { messages } = await c.req.json();

  const result = streamText({
    model: upstream.chatModel('uptime-crew-assistant'),
    system:
      'You are an assistant that helps engineers track multi-state tax compliance. ' +
      'When asked about a tenant, call lookupTenant first. When asked whether a ' +
      'tenant has nexus in a specific state, call nexusForState with the two-letter ' +
      'state code.',
    messages,
    abortSignal: c.req.raw.signal,
  });

  return result.toDataStreamResponse({
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    },
  });
});
