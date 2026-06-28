import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { TenantChatPanel } from '../pages/TenantChatPanel';
import { server } from './server';

let tenantSeq = 0;
function renderPanel(): string {
  tenantSeq += 1;
  const tenantId = `stub-${tenantSeq}-${Date.now()}`;
  render(
    <MemoryRouter initialEntries={[`/tenants/${tenantId}/chat`]}>
      <Routes>
        <Route path="/tenants/:id/chat" element={<TenantChatPanel />} />
      </Routes>
    </MemoryRouter>,
  );
  return tenantId;
}

describe('TenantChatPanel', () => {
  it('renders the chat transcript and input form', () => {
    renderPanel();
    expect(screen.getByRole('log', { name: 'chat-transcript' })).toBeInTheDocument();
    expect(screen.getByRole('form', { name: 'chat-form' })).toBeInTheDocument();
  });

  it('disables Send when the input is empty', () => {
    renderPanel();
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  it('disables Send when the input is whitespace-only', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.type(screen.getByLabelText('chat-input'), '   ');
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  it('enables Send once non-whitespace input is present', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.type(screen.getByLabelText('chat-input'), 'hi');
    expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled();
  });

  it('Stop is disabled and Regenerate is enabled when idle', () => {
    renderPanel();
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Regenerate' })).toBeEnabled();
  });

  it('streams the stub assistant reply token-by-token', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByLabelText('chat-input'), 'hello');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() =>
      expect(screen.getByText(/stub tenant reply\./)).toBeInTheDocument(),
    );
  });

  it('renders the assistant reply on a li with data-role="assistant"', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByLabelText('chat-input'), 'hello');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    const assistantLi = await waitFor(() => {
      const li = document.querySelector('li[data-role="assistant"]');
      expect(li).not.toBeNull();
      return li!;
    });
    expect(assistantLi).toHaveTextContent(/stub tenant reply\./);
  });

  it('renders the user message on a li with data-role="user"', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByLabelText('chat-input'), 'hello there');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => {
      const li = document.querySelector('li[data-role="user"]');
      expect(li).not.toBeNull();
      expect(li!).toHaveTextContent('hello there');
    });
  });

  it('Stop mid-stream halts growth and the partial text remains', async () => {
    // Replace the default handler with one that emits a single token and
    // then awaits the request's abort signal — so the stream stays open
    // until the panel calls stop().
    server.use(
      http.post('/api/chat', ({ request }) => {
        const encoder = new TextEncoder();
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(encoder.encode(`0:${JSON.stringify('partial ')}\n`));
            request.signal.addEventListener('abort', () => {
              try { controller.close(); } catch { /* already closed */ }
            });
          },
        });
        return new HttpResponse(stream, {
          headers: {
            'Content-Type':            'text/event-stream',
            'X-Vercel-AI-Data-Stream': 'v1',
          },
        });
      }),
    );

    const user = userEvent.setup();
    renderPanel();
    await user.type(screen.getByLabelText('chat-input'), 'hi');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    // Wait for the partial token to land so we know the stream is live.
    await waitFor(() =>
      expect(screen.getByText(/partial/)).toBeInTheDocument(),
    );

    const stopBtn = screen.getByRole('button', { name: 'Stop' });
    await waitFor(() => expect(stopBtn).toBeEnabled());
    await user.click(stopBtn);

    // After stop, the spinner is gone and the text has not grown beyond
    // the single token we emitted.
    await waitFor(() =>
      expect(screen.queryByRole('status')).not.toBeInTheDocument(),
    );
    const assistantLi = document.querySelector('li[data-role="assistant"]');
    expect(assistantLi).not.toBeNull();
    expect(assistantLi!.textContent ?? '').toMatch(/partial/);
    expect(assistantLi!.textContent ?? '').not.toMatch(/stub tenant reply/);
  });

  it('Regenerate fires a second POST to /api/chat', async () => {
    const seen: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith('/api/chat')) {
        seen.push(request.url);
      }
    });

    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByLabelText('chat-input'), 'hello');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() =>
      expect(screen.getByText(/stub tenant reply\./)).toBeInTheDocument(),
    );

    const firstCount = seen.length;
    expect(firstCount).toBeGreaterThanOrEqual(1);

    await user.click(screen.getByRole('button', { name: 'Regenerate' }));
    await waitFor(() => expect(seen.length).toBeGreaterThan(firstCount));

    server.events.removeAllListeners('request:start');
  });

  it('calls scrollIntoView when a new message lands', async () => {
    const spy = vi.spyOn(Element.prototype, 'scrollIntoView');
    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByLabelText('chat-input'), 'hello');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() =>
      expect(screen.getByText(/stub tenant reply\./)).toBeInTheDocument(),
    );

    expect(spy).toHaveBeenCalledWith({ behavior: 'smooth' });
    spy.mockRestore();
  });
});
