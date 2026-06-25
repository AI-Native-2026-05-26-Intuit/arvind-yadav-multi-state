import { http, HttpResponse } from 'msw';

// Encode Vercel-AI-SDK data-stream protocol frames:
//   0:"text chunk"      -- a text delta
//   d:{finishReason...} -- the finish event
// followed by stream close (the SDK does not require a literal [DONE]).
const encoder = new TextEncoder();

function encodeFrame(prefix: string, payload: unknown): Uint8Array {
  const line = `${prefix}:${JSON.stringify(payload)}\n`;
  return encoder.encode(line);
}

export const sseHandlers = [
  http.post('/api/chat', () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encodeFrame('0', 'stub '));
        controller.enqueue(encodeFrame('0', 'tenant '));
        controller.enqueue(encodeFrame('0', 'reply.'));
        controller.enqueue(
          encodeFrame('d', {
            finishReason: 'stop',
            usage: { promptTokens: 1, completionTokens: 3 },
          }),
        );
        controller.close();
      },
    });

    return new HttpResponse(stream, {
      headers: {
        'Content-Type':            'text/event-stream',
        'Cache-Control':           'no-cache, no-transform',
        'X-Vercel-AI-Data-Stream': 'v1',
      },
    });
  }),
];
