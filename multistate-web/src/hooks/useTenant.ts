import { useEffect, useState } from 'react';
import type { Tenant } from '../types/tenant';

type State =
  | { status: 'loading' }
  | { status: 'ok';    data:  Tenant }
  | { status: 'error'; error: string };

/**
 * Stub data source for W4 D1. The real Apollo Client query lands on W4 D3.
 *
 * @param id — the tenant id; today only "stub-id-1" returns data.
 */
export function useTenant(id: string): {
  data:    Tenant | null;
  loading: boolean;
  error:   string | null;
} {
  const [state, setState] = useState<State>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    fetch(`/mocks/tenant.json`)
      .then((res) => {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json() as Promise<Tenant>;
      })
      .then((data) => { if (!cancelled) setState({ status: 'ok', data }); })
      .catch((err: unknown) => {
        if (cancelled) return;
        const message = err instanceof Error ? err.message : String(err);
        setState({ status: 'error', error: message });
      });
    return () => { cancelled = true; };
  }, [id]);

  return {
    data:    state.status === 'ok'    ? state.data  : null,
    loading: state.status === 'loading',
    error:   state.status === 'error' ? state.error : null,
  };
}
