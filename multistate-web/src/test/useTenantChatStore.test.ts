import { describe, it, expect, beforeEach, vi } from 'vitest';
import type { Message } from 'ai';
import { useTenantChatStore } from '../stores/useTenantChatStore';

function makeAssistantMessage(overrides: Partial<Message> = {}): Message {
  return {
    id:        'msg-1',
    role:      'assistant',
    content:   'stub tenant reply.',
    createdAt: new Date('2025-01-01T00:00:00Z'),
    ...overrides,
  };
}

beforeEach(() => {
  useTenantChatStore.getState().clear();
  localStorage.clear();
});

describe('useTenantChatStore', () => {
  it('starts with an empty messages array', () => {
    expect(useTenantChatStore.getState().messages).toEqual([]);
  });

  it('appendAssistantMessage pushes a message onto state.messages', () => {
    useTenantChatStore.getState().appendAssistantMessage(makeAssistantMessage());
    const { messages } = useTenantChatStore.getState();
    expect(messages).toHaveLength(1);
    expect(messages[0]?.content).toBe('stub tenant reply.');
  });

  it('appendAssistantMessage preserves order across multiple appends', () => {
    const m1 = makeAssistantMessage({ id: 'm-1', content: 'first' });
    const m2 = makeAssistantMessage({ id: 'm-2', content: 'second' });
    useTenantChatStore.getState().appendAssistantMessage(m1);
    useTenantChatStore.getState().appendAssistantMessage(m2);
    expect(useTenantChatStore.getState().messages.map((m) => m.content)).toEqual([
      'first',
      'second',
    ]);
  });

  it('clear() empties the messages array', () => {
    useTenantChatStore.getState().appendAssistantMessage(makeAssistantMessage());
    useTenantChatStore.getState().clear();
    expect(useTenantChatStore.getState().messages).toEqual([]);
  });

  it('writes appended messages into localStorage under uc:tenant-chat', () => {
    useTenantChatStore
      .getState()
      .appendAssistantMessage(makeAssistantMessage({ content: 'persist me' }));
    const raw = localStorage.getItem('uc:tenant-chat');
    expect(raw).not.toBeNull();
    expect(raw).toMatch(/persist me/);
  });

  it('persist round-trip: a re-imported store reads back the appended message', async () => {
    useTenantChatStore
      .getState()
      .appendAssistantMessage(
        makeAssistantMessage({ id: 'rehydrate-me', content: 'rehydrated content' }),
      );

    // Drop the cached module so the next import re-runs create() and
    // re-reads localStorage via the persist middleware.
    vi.resetModules();
    const fresh = await import('../stores/useTenantChatStore');

    expect(fresh.useTenantChatStore.getState().messages).toHaveLength(1);
    expect(fresh.useTenantChatStore.getState().messages[0]?.content).toBe(
      'rehydrated content',
    );
  });
});
