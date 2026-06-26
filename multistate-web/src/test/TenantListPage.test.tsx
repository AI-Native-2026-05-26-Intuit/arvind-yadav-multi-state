import { describe, it, expect } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import { gql } from '@apollo/client';
import { renderWithProviders } from './renderWithProviders';
import { TenantListPage } from '../pages/TenantListPage';

// MockedProvider short-circuits the Apollo fetch path, sidestepping the
// jsdom/undici AbortSignal mismatch that bites real HttpLink + MSW.
const LATEST_TENANTS = gql`
  query LatestTenants {
    latestTenants(limit: 20) {
      id
      name
      updatedAt
    }
  }
`;

const tenants = [
  { __typename: 'Tenant', id: 'stub-1', name: 'Acme Corp',   updatedAt: '2025-01-01T00:00:00Z' },
  { __typename: 'Tenant', id: 'stub-2', name: 'Globex LLC',  updatedAt: '2025-01-02T00:00:00Z' },
  { __typename: 'Tenant', id: 'stub-3', name: 'Initech Inc', updatedAt: '2025-01-03T00:00:00Z' },
];

const successMocks = [
  { request: { query: LATEST_TENANTS }, result: { data: { latestTenants: tenants } } },
];

const emptyMocks = [
  { request: { query: LATEST_TENANTS }, result: { data: { latestTenants: [] } } },
];

const errorMocks = [
  {
    request: { query: LATEST_TENANTS },
    result: { errors: [{ message: 'Network down' }] },
  },
];

const networkErrorMocks = [
  {
    request: { query: LATEST_TENANTS },
    error: new Error('Boom'),
  },
];

describe('TenantListPage', () => {
  it('renders the page heading', async () => {
    renderWithProviders(<TenantListPage />, { apolloMocks: successMocks });
    expect(screen.getByRole('heading', { name: /tenants/i })).toBeInTheDocument();
  });

  it('shows the loading status before data resolves', () => {
    renderWithProviders(<TenantListPage />, { apolloMocks: successMocks });
    expect(screen.getByRole('status', { name: /loading…/i })).toBeInTheDocument();
  });

  it('renders the first row by its accessible name after the query resolves', async () => {
    renderWithProviders(<TenantListPage />, { apolloMocks: successMocks });
    const link = await screen.findByRole('link', { name: 'Acme Corp' });
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute('href', '/tenants/stub-1');
  });

  it('renders every tenant row from the mock', async () => {
    renderWithProviders(<TenantListPage />, { apolloMocks: successMocks });
    await screen.findByRole('link', { name: 'Acme Corp' });
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });

  it('shows the empty-state status when the server returns no rows', async () => {
    renderWithProviders(<TenantListPage />, { apolloMocks: emptyMocks });
    expect(
      await screen.findByRole('status', { name: /no results/i }),
    ).toBeInTheDocument();
  });

  it('surfaces a GraphQL error through role=alert', async () => {
    renderWithProviders(<TenantListPage />, { apolloMocks: errorMocks });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/network down/i);
  });

  it('surfaces a network error through role=alert', async () => {
    renderWithProviders(<TenantListPage />, { apolloMocks: networkErrorMocks });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/boom/i);
  });

  it('narrows the visible rows when the user types in the search input', async () => {
    const { user } = renderWithProviders(<TenantListPage />, { apolloMocks: successMocks });
    await screen.findByRole('link', { name: 'Acme Corp' });

    const search = screen.getByLabelText(/search/i);
    await user.type(search, 'globex');

    await waitFor(() => {
      expect(screen.queryByRole('link', { name: 'Acme Corp' })).not.toBeInTheDocument();
    });
    expect(screen.getByRole('link', { name: 'Globex LLC' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('falls back to the empty state when the search text matches nothing', async () => {
    const { user } = renderWithProviders(<TenantListPage />, { apolloMocks: successMocks });
    await screen.findByRole('link', { name: 'Acme Corp' });

    const search = screen.getByLabelText(/search/i);
    await user.type(search, 'zzznomatch');

    expect(
      await screen.findByRole('status', { name: /no results/i }),
    ).toBeInTheDocument();
  });

  it('renders each row inside the tenant-list ul', async () => {
    renderWithProviders(<TenantListPage />, { apolloMocks: successMocks });
    const list = await screen.findByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(3);
  });
});
