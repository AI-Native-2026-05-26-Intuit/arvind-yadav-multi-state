import type { Message } from 'ai';
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

// Same memory fallback rationale as useTenantFilterStore — persist
// middleware captures `storage` once at creation, so an undefined return
// would crash on the first set() under jsdom.
const memoryStorage = (() => {
  const store = new Map<string, string>();
  return {
    getItem:    (k: string) => store.get(k) ?? null,
    setItem:    (k: string, v: string) => { store.set(k, v); },
    removeItem: (k: string) => { store.delete(k); },
  };
})();

const safeStorage = createJSONStorage(() =>
  typeof window !== 'undefined' && window.localStorage
    ? window.localStorage
    : memoryStorage,
);

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
