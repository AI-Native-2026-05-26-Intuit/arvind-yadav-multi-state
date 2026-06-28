import type { ReactElement, ReactNode } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApolloClient, HttpLink, InMemoryCache } from '@apollo/client';
import { ApolloProvider } from '@apollo/client/react';
import { MockedProvider } from '@apollo/client/testing/react';
import type { MockedResponse } from '@apollo/client/testing';

interface ProviderOptions {
  readonly initialEntries?: readonly string[];
  // Apollo MockedProvider mocks — component-test mode.
  readonly apolloMocks?: ReadonlyArray<MockedResponse>;
  // Real Apollo client — integration-test mode (hits MSW via HttpLink).
  readonly apolloClient?: ApolloClient;
  readonly queryClient?: QueryClient;
}

export function makeIntegrationApolloClient(): ApolloClient {
  return new ApolloClient({
    link: new HttpLink({ uri: 'http://localhost:8080/graphql' }),
    cache: new InMemoryCache({
      typePolicies: { Tenant: { keyFields: ['id'] } },
    }),
  });
}

export function renderWithProviders(
  ui: ReactElement,
  opts: ProviderOptions & Omit<RenderOptions, 'wrapper'> = {},
) {
  const {
    initialEntries = ['/tenants'],
    apolloMocks = [],
    apolloClient,
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    }),
    ...rtl
  } = opts;

  const user = userEvent.setup();
  const utils = render(ui, {
    wrapper: ({ children }: { children: ReactNode }) => {
      const apolloLayer = apolloClient ? (
        <ApolloProvider client={apolloClient}>{children}</ApolloProvider>
      ) : (
        <MockedProvider mocks={[...apolloMocks]}>{children}</MockedProvider>
      );
      return (
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[...initialEntries]}>
            {apolloLayer}
          </MemoryRouter>
        </QueryClientProvider>
      );
    },
    ...rtl,
  });

  return { user, queryClient, apolloClient, ...utils };
}
