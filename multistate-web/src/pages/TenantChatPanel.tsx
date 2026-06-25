import type { ReactElement } from 'react';
import { useEffect, useRef } from 'react';
import { useChat } from '@ai-sdk/react';
import { useParams } from 'react-router-dom';
import { useTenantChatStore } from '../stores/useTenantChatStore';

export function TenantChatPanel(): ReactElement {
  const { id = '' } = useParams<{ id: string }>();
  const persist = useTenantChatStore((s) => s.appendAssistantMessage);

  const {
    messages,
    input,
    handleInputChange,
    handleSubmit,
    isLoading,
    stop,
    reload,
    error,
  } = useChat({
    api: '/api/chat',
    id: `tenant-${id}`,
    // CRITICAL: only persist on completion. Writing partial tokens to
    // Zustand mid-stream breaks the persist middleware's rehydration;
    // see §9.
    onFinish: (msg) => {
      persist(msg);
    },
  });

  const endRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <section aria-label="tenant-chat">
      <ul aria-label="chat-transcript">
        {messages.map((m) => (
          <li key={m.id} data-role={m.role}>
            <strong>{m.role}:</strong> {m.content}
          </li>
        ))}
      </ul>
      <div ref={endRef} />

      {isLoading && <p role="status">Assistant is replying…</p>}
      {error && <p role="alert">Error: {error.message}</p>}

      <form aria-label="chat-input" onSubmit={handleSubmit}>
        <input
          aria-label="chat-message"
          value={input}
          onChange={handleInputChange}
          disabled={isLoading}
        />
        <button type="submit" disabled={isLoading || input.length === 0}>
          Send
        </button>
        <button type="button" onClick={stop} disabled={!isLoading}>
          Stop
        </button>
        <button type="button" onClick={() => void reload()} disabled={isLoading}>
          Regenerate
        </button>
      </form>
    </section>
  );
}
