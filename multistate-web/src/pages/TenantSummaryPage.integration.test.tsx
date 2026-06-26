import { describe, it, expect } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { server } from '../test/server';
import {
  tenantHandlers,
  tenantErrorHandler,
  tenantErrorHandlerProxied,
  apolloLoadingHandler,
  apolloErrorHandler,
} from '../test/handlers';
import {
  renderWithProviders,
  makeIntegrationApolloClient,
} from '../test/renderWithProviders';
import { TenantTable } from './TenantTable';
import { TenantListPage } from './TenantListPage';

// MSW-driven integration tests. These exercise the wiring from the page
// through TanStack Query / Apollo HttpLink to the network, rather than
// stubbing the data layer like the component tests in
// TenantListPage.test.tsx do.
describe('TenantSummaryPage – integration via MSW', () => {
  it('renders the first REST row as a table cell once the hook resolves', async () => {
    server.use(...tenantHandlers);
    renderWithProviders(<TenantTable />);

    expect(
      await screen.findByRole('cell', { name: /Stub Tenant 01/i }),
    ).toBeInTheDocument();
  });

  it('renders every REST row', async () => {
    server.use(...tenantHandlers);
    renderWithProviders(<TenantTable />);

    await screen.findByRole('cell', { name: /Stub Tenant 01/i });
    expect(screen.getByRole('cell', { name: /Stub Tenant 02/i })).toBeInTheDocument();
    // 2 data rows + 1 header row = 3 <tr>s
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  it('shows the REST loading status before the cells appear', async () => {
    server.use(...tenantHandlers);
    renderWithProviders(<TenantTable />);

    expect(screen.getByRole('status', { name: /loading…/i })).toBeInTheDocument();
    await screen.findByRole('cell', { name: /Stub Tenant 01/i });
  });

  it('surfaces an alert when the REST endpoint returns 500', async () => {
    server.use(tenantErrorHandler, tenantErrorHandlerProxied);
    renderWithProviders(<TenantTable />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/failed to load tenants/i);
  });

  it('shows the Apollo loading skeleton before the GraphQL data resolves', async () => {
    server.use(apolloLoadingHandler);
    renderWithProviders(<TenantListPage />, {
      apolloClient: makeIntegrationApolloClient(),
    });

    expect(
      await screen.findByRole('status', { name: /loading…/i }),
    ).toBeInTheDocument();
  });

  it('renders the Apollo happy path through real HttpLink + MSW', async () => {
    server.use(...tenantHandlers);
    renderWithProviders(<TenantListPage />, {
      apolloClient: makeIntegrationApolloClient(),
    });

    expect(
      await screen.findByRole('cell', { name: 'Stub Tenant 01' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Stub Tenant 02' })).toBeInTheDocument();
  });

  it('serves the Apollo response from cache on a remount with the same client', async () => {
    server.use(...tenantHandlers);
    const client = makeIntegrationApolloClient();

    const first = renderWithProviders(<TenantListPage />, { apolloClient: client });
    await screen.findByRole('cell', { name: 'Stub Tenant 01' });
    first.unmount();

    // Force the network layer to fail — if Apollo still serves the answer,
    // it's reading the InMemoryCache from the first render.
    server.use(apolloErrorHandler);
    renderWithProviders(<TenantListPage />, { apolloClient: client });

    expect(
      await screen.findByRole('cell', { name: 'Stub Tenant 01' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('serves REST data from cache on remount with the same QueryClient', async () => {
    server.use(...tenantHandlers);
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } },
    });

    const first = renderWithProviders(<TenantTable />, { queryClient });
    await screen.findByRole('cell', { name: /Stub Tenant 01/i });
    first.unmount();

    // Even after pointing the network at the 500 branch, the cache hit
    // means we never refetch and the rows stay rendered.
    server.use(tenantErrorHandler, tenantErrorHandlerProxied);
    renderWithProviders(<TenantTable />, { queryClient });

    expect(
      await screen.findByRole('cell', { name: /Stub Tenant 01/i }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('narrows the visible REST cells when the engineer types into the filter', async () => {
    server.use(...tenantHandlers);
    const { user } = renderWithProviders(<TenantTable />);

    await screen.findByRole('cell', { name: /Stub Tenant 01/i });

    await user.type(
      screen.getByRole('searchbox', { name: /filter/i }),
      'tenant 02',
    );

    await waitFor(() => {
      expect(
        screen.queryByRole('cell', { name: /Stub Tenant 01/i }),
      ).not.toBeInTheDocument();
    });
    expect(screen.getByRole('cell', { name: /Stub Tenant 02/i })).toBeInTheDocument();
  });

  it('renders no data rows when the REST filter matches nothing', async () => {
    server.use(...tenantHandlers);
    const { user } = renderWithProviders(<TenantTable />);

    const table = await screen.findByRole('table');
    await screen.findByRole('cell', { name: /Stub Tenant 01/i });

    await user.type(
      screen.getByRole('searchbox', { name: /filter/i }),
      'zzznomatch',
    );

    await waitFor(() => {
      // Header row only — no data rows.
      expect(within(table).getAllByRole('row')).toHaveLength(1);
    });
  });

  it('narrows the Apollo list when the engineer types in the search box', async () => {
    server.use(...tenantHandlers);
    const { user } = renderWithProviders(<TenantListPage />, {
      apolloClient: makeIntegrationApolloClient(),
    });

    await screen.findByRole('cell', { name: 'Stub Tenant 01' });

    await user.type(screen.getByLabelText(/search/i), 'tenant 02');

    await waitFor(() => {
      expect(
        screen.queryByRole('cell', { name: 'Stub Tenant 01' }),
      ).not.toBeInTheDocument();
    });
    expect(screen.getByRole('cell', { name: 'Stub Tenant 02' })).toBeInTheDocument();
  });

  it('surfaces a GraphQL error envelope through role=alert when the network branch errors', async () => {
    server.use(apolloErrorHandler);
    renderWithProviders(<TenantListPage />, {
      apolloClient: makeIntegrationApolloClient(),
    });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/apollo boom/i);
  });

  it('keeps the searchbox value in the cross-component filter store', async () => {
    server.use(...tenantHandlers);
    const { user } = renderWithProviders(<TenantTable />);

    await screen.findByRole('cell', { name: /Stub Tenant 01/i });
    const search = screen.getByRole('searchbox', { name: /filter/i });
    await user.type(search, 'globex');

    // Same store, separate component: TenantListPage should read the value.
    renderWithProviders(<TenantListPage />, {
      apolloClient: makeIntegrationApolloClient(),
    });
    expect(screen.getAllByLabelText(/search/i)[0]).toHaveValue('globex');
  });
});
