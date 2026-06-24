import { useEffect, useReducer, useState } from 'react';
import type { ReactElement } from 'react';
import type { Tenant } from '../types/tenant';
import { ThresholdSlider } from '../components/ThresholdSlider';
import { ThresholdReadout } from '../components/ThresholdReadout';
import { FilterStrip } from '../components/FilterStrip';
import { useDebouncedSearch } from '../hooks/useDebouncedSearch';
import {
  detailReducer,
  INITIAL_DETAIL_STATE,
  type DetailState,
} from './TenantDetailPage.reducer';

export function TenantDetailPage(): ReactElement {
  const [state, dispatch] = useReducer(detailReducer, INITIAL_DETAIL_STATE);
  const debouncedSearch   = useDebouncedSearch();
  // Throw during render (not in the click handler) so the ErrorBoundary
  // actually catches it — React boundaries don't catch event-handler throws.
  const [shouldThrow, setShouldThrow] = useState(false);
  if (shouldThrow) throw new Error('Dev-triggered render error');

  useEffect(() => {
    let cancelled = false;
    dispatch({ type: 'fetch/start' });

    fetch('/mocks/tenant.json')
      .then((res) => {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json() as Promise<Tenant | null>;
      })
      .then((payload) => {
        if (cancelled) return;
        dispatch({ type: 'fetch/success', payload });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const message = err instanceof Error ? err.message : String(err);
        dispatch({ type: 'fetch/error', error: message });
      });

    return () => { cancelled = true; };
  }, []);

  return (
    <>
      <FilterStrip />
      {import.meta.env.DEV && (
        <button type="button" onClick={() => setShouldThrow(true)}>
          Trigger error
        </button>
      )}
      {debouncedSearch !== '' && (
        <p role="status" aria-label="search-filter">
          filtering for: '{debouncedSearch}'
        </p>
      )}
      <DetailCard state={state} />
    </>
  );
}

function DetailCard({ state }: { readonly state: DetailState }): ReactElement {
  switch (state.status) {
    case 'idle':
    case 'loading':
      return <p>Loading…</p>;
    case 'error':
      return <p>Failed to load: {state.error}</p>;
    case 'empty':
      return <p>Not found.</p>;
    case 'success':
      return (
        <main>
          <h1>Tenant {state.data.id}</h1>
          <dl>
            <dt>primaryState</dt>    <dd>{state.data.primaryState}</dd>
            <dt>stateCount</dt>      <dd>{state.data.stateCount}</dd>
            <dt>totalAllocation</dt> <dd>{state.data.totalAllocation}</dd>
          </dl>

          <section>
            <ThresholdSlider />
            <ThresholdReadout />
          </section>

          <section>
            <h2>Lines</h2>
            <ul>
              {state.data.lines.map((line) => (
                <li key={line.id}>{line.id}: {line.amount}</li>
              ))}
            </ul>
          </section>
        </main>
      );
  }
}
