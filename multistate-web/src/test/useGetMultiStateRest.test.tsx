import { describe, it, expect } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement, ReactNode } from 'react';
import { useGetMultiStateRest } from '../hooks/useGetMultiStateRest';

function wrapperFor(): (props: { children: ReactNode }) => ReactElement {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return ({ children }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('useGetMultiStateRest', () => {
  it('resolves the stub tenant from the MSW REST handler', async () => {
    const { result } = renderHook(() => useGetMultiStateRest('abc-123'), {
      wrapper: wrapperFor(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual({
      id: 'abc-123',
      name: 'stub tenant',
      updatedAt: '2025-01-04T00:00:00Z',
    });
  });

  it('stays disabled when id is empty', () => {
    const { result } = renderHook(() => useGetMultiStateRest(''), {
      wrapper: wrapperFor(),
    });
    expect(result.current.fetchStatus).toBe('idle');
  });
});
