import { create } from 'zustand';
import { devtools, persist, createJSONStorage } from 'zustand/middleware';

// In-memory fallback for environments where window.localStorage is not
// available (jsdom-based tests without a configured URL, SSR). Without this
// the persist middleware crashes on the first set() because it captures
// `storage = getStorage()` once at creation; an undefined return there
// leaves every later setItem call dereferencing undefined.
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

type FilterState = {
  readonly stateFilter:     ReadonlyArray<string>;
  readonly dateRange:       readonly [string, string | null];
  readonly searchText:      string;
  readonly includeArchived: boolean;
  readonly threshold:       number;
};

type FilterActions = {
  readonly setStateFilter:     (next: ReadonlyArray<string>) => void;
  readonly setSearchText:      (next: string)                => void;
  readonly setThreshold:       (next: number)                => void;
  readonly setIncludeArchived: (next: boolean)               => void;
  readonly reset:              ()                            => void;
};

const INITIAL: FilterState = {
  stateFilter:     [],
  dateRange:       ['', null],
  searchText:      '',
  includeArchived: false,
  threshold:       50,
};

// Only `threshold` is persisted across reloads — see partialize below.
// Search text persisted across reloads would be a UX bug (engineer types
// "foo" for an unrelated reason, returns next week, sees results still
// filtered by "foo"); the filter chips and date range are session state.
export const useTenantFilterStore = create<FilterState & FilterActions>()(
  devtools(
    persist(
      (set) => ({
        ...INITIAL,
        setStateFilter: (next) =>
          set({ stateFilter: next }, false, 'filters/setStateFilter'),
        setSearchText: (next) =>
          set({ searchText: next }, false, 'filters/setSearchText'),
        setThreshold: (next) =>
          set({ threshold: next }, false, 'filters/setThreshold'),
        setIncludeArchived: (next) =>
          set({ includeArchived: next }, false, 'filters/setIncludeArchived'),
        reset: () => set(INITIAL, false, 'filters/reset'),
      }),
      {
        name: 'multistate-web:filters',
        storage: safeStorage,
        // partialize: only `threshold` survives a reload.
        partialize: (s) => ({ threshold: s.threshold }),
      },
    ),
    { name: 'useTenantFilterStore' },
  ),
);
