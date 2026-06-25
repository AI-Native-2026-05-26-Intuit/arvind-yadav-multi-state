import type { Message } from '@ai-sdk/react';
import { create } from 'zustand';
import { devtools, persist, createJSONStorage } from 'zustand/middleware';

// Same memory fallback rationale as useTenantFilterStore — persist
// middleware captures `storage` once at creation, so an undefined return
// would crash on the first set().
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

type ChatState = {
  readonly assistantMessages: ReadonlyArray<Message>;
};

type ChatActions = {
  readonly appendAssistantMessage: (msg: Message) => void;
  readonly clear:                  () => void;
};

const INITIAL: ChatState = {
  assistantMessages: [],
};

// Only completed assistant messages land here. Writing partial tokens
// mid-stream would defeat the persist middleware's rehydration (each
// keystroke would rewrite the whole serialized blob).
export const useTenantChatStore = create<ChatState & ChatActions>()(
  devtools(
    persist(
      (set) => ({
        ...INITIAL,
        appendAssistantMessage: (msg) =>
          set(
            (s) => ({ assistantMessages: [...s.assistantMessages, msg] }),
            false,
            'chat/appendAssistantMessage',
          ),
        clear: () => set(INITIAL, false, 'chat/clear'),
      }),
      {
        name: 'multistate-web:tenant-chat',
        storage: safeStorage,
        partialize: (s) => ({ assistantMessages: s.assistantMessages }),
      },
    ),
    { name: 'useTenantChatStore' },
  ),
);
