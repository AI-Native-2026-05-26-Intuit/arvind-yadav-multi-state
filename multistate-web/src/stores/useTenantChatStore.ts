import type { Message } from 'ai';
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

// jsdom defines localStorage as a lazy getter on Window.prototype, which
// means `window.localStorage` may evaluate to undefined the first time
// the module loads (before vitest has fully populated the env) but
// resolve correctly later. createJSONStorage captures its storage once
// at creation, so we hand it a permanent proxy that defers the lookup
// to every call — and falls back to an in-memory map when neither
// localStorage is available (SSR, edge runners).
const memoryStorage = (() => {
  const store = new Map<string, string>();
  return {
    getItem:    (k: string) => store.get(k) ?? null,
    setItem:    (k: string, v: string) => { store.set(k, v); },
    removeItem: (k: string) => { store.delete(k); },
  };
})();

function resolveStorage(): {
  getItem: (k: string) => string | null;
  setItem: (k: string, v: string) => void;
  removeItem: (k: string) => void;
} {
  if (typeof globalThis !== 'undefined' && (globalThis as { localStorage?: Storage }).localStorage) {
    return (globalThis as unknown as { localStorage: Storage }).localStorage;
  }
  if (typeof window !== 'undefined' && window.localStorage) {
    return window.localStorage;
  }
  return memoryStorage;
}

const deferredStorage = {
  getItem:    (k: string) => resolveStorage().getItem(k),
  setItem:    (k: string, v: string) => resolveStorage().setItem(k, v),
  removeItem: (k: string) => resolveStorage().removeItem(k),
};

const safeStorage = createJSONStorage(() => deferredStorage);

interface UseTenantChatStoreState {
  readonly messages: readonly Message[];
  appendAssistantMessage: (m: Message) => void;
  clear: () => void;
}

// CRITICAL: only completed assistant messages land here, written from
// useChat's onFinish callback. Writing partial tokens mid-stream (e.g.
// from an onMessageStream handler) would rewrite the entire serialized
// blob on every token and defeat persist's rehydration — see §9.
export const useTenantChatStore = create<UseTenantChatStoreState>()(
  persist(
    (set) => ({
      messages: [],
      appendAssistantMessage: (m) =>
        set((s) => ({ messages: [...s.messages, m] })),
      clear: () => set({ messages: [] }),
    }),
    {
      name: 'uc:tenant-chat',
      storage: safeStorage,
    },
  ),
);
