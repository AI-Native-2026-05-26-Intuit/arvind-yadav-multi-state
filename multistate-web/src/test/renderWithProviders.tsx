import type { ReactElement, ReactNode } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MockedProvider } from '@apollo/client/testing/react';
import type { MockedResponse } from '@apollo/client/testing';

interface ProviderOptions {
  readonly initialEntries?: readonly string[];
  readonly apolloMocks?: ReadonlyArray<MockedResponse>;
  readonly queryClient?: QueryClient;
}

export function renderWithProviders(
  ui: ReactElement,
  opts: ProviderOptions & Omit<RenderOptions, 'wrapper'> = {},
) {
  const {
    initialEntries = ['/tenants'],
    apolloMocks = [],
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    }),
    ...rtl
  } = opts;

  const user = userEvent.setup();
  const utils = render(ui, {
    wrapper: ({ children }: { children: ReactNode }) => (
      <MockedProvider mocks={[...apolloMocks]}>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[...initialEntries]}>
            {children}
          </MemoryRouter>
        </QueryClientProvider>
      </MockedProvider>
    ),
    ...rtl,
  });

  return { user, queryClient, ...utils };
}
