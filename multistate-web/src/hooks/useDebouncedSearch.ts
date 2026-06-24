import { useEffect, useState } from 'react';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';

/**
 * Reads `searchText` from the Zustand filter store and returns a value that
 * lags the source by `delayMs` milliseconds. Cleanup clears the pending
 * timer on every keystroke and on unmount; without that cleanup the timer
 * fires after the component is gone.
 */
export function useDebouncedSearch(delayMs = 300): string {
  const searchText = useTenantFilterStore((s) => s.searchText);
  const [debounced, setDebounced] = useState(searchText);

  useEffect(() => {
    const t = setTimeout(() => setDebounced(searchText), delayMs);
    return () => clearTimeout(t);
  }, [searchText, delayMs]);

  return debounced;
}
