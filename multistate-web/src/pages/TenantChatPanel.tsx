import type { ReactElement } from 'react';
import { useEffect, useRef } from 'react';
import { useChat } from '@ai-sdk/react';
import { useParams } from 'react-router-dom';
import { useTenantChatStore } from '../stores/useTenantChatStore';
import { ToolCallCard } from './ToolCallCard';

export function TenantChatPanel(): ReactElement {
  const { id = '' } = useParams<{ id: string }>();
  const persist = useTenantChatStore((s) => s.appendAssistantMessage);
  // Snapshot the persisted history once at mount — passing the live
  // selector result to initialMessages would reset useChat on every
  // store write and replay the conversation.
  const rehydrated = useTenantChatStore.getState().messages;

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
    initialMessages: [...rehydrated],
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
      <ul role="log" aria-label="chat-transcript" aria-live="polite">
        {messages.map((m) => (
          <li key={m.id} data-role={m.role}>
            <strong>{m.role}:</strong> {m.content}
            {(m.toolInvocations ?? []).map((inv) => (
              <ToolCallCard key={inv.toolCallId} invocation={inv} />
            ))}
          </li>
        ))}
      </ul>
      <div ref={endRef} />

      {isLoading && <p role="status">Assistant is replying...</p>}
      {error && <p role="alert">Error: {error.message}</p>}

      <form aria-label="chat-form" onSubmit={handleSubmit}>
        <input
          aria-label="chat-input"
          value={input}
          onChange={handleInputChange}
          disabled={isLoading}
        />
        <button type="submit" disabled={isLoading || input.trim() === ''}>
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
