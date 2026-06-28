import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { gql } from '@apollo/client';
import { renderWithProviders } from './renderWithProviders';
import { TenantListPage } from '../pages/TenantListPage';
import { TenantTable } from '../pages/TenantTable';
import { server } from './server';
import { tenantHandlers } from './handlers';

// Focused W4 D5 a11y file: tab order + axe on the surfaces engineers
// actually touch first. One scan per surface; the Playwright spec covers
// the chat panel state.

const LATEST_TENANTS = gql`
  query LatestTenants {
    latestTenants(limit: 20) {
      id
      name
      updatedAt
    }
  }
`;

const successMocks = [
  {
    request: { query: LATEST_TENANTS },
    result: {
      data: {
        latestTenants: [
          { __typename: 'Tenant', id: 'stub-1', name: 'Acme Corp',  updatedAt: '2025-01-01T00:00:00Z' },
          { __typename: 'Tenant', id: 'stub-2', name: 'Globex LLC', updatedAt: '2025-01-02T00:00:00Z' },
        ],
      },
    },
  },
];

describe('Tenant surfaces – a11y', () => {
  it('focuses the search input first when the engineer tabs through TenantListPage', async () => {
    const { user } = renderWithProviders(<TenantListPage />, { apolloMocks: successMocks });
    await screen.findByRole('cell', { name: 'Acme Corp' });

    await user.tab();
    expect(screen.getByLabelText(/search/i)).toHaveFocus();
  });

  it('moves focus to the filter searchbox on the second Tab on TenantTable', async () => {
    server.use(...tenantHandlers);
    const { user } = renderWithProviders(<TenantTable />);
    await screen.findByRole('cell', { name: /Stub Tenant 01/i });

    await user.tab();
    expect(screen.getByRole('searchbox', { name: /filter/i })).toHaveFocus();
  });

  it('passes axe on the REST-backed TenantTable happy-path render', async () => {
    server.use(...tenantHandlers);
    const { container } = renderWithProviders(<TenantTable />);
    await screen.findByRole('cell', { name: /Stub Tenant 01/i });

    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it('passes axe on the TenantListPage empty state', async () => {
    const { container } = renderWithProviders(<TenantListPage />, {
      apolloMocks: [
        {
          request: { query: LATEST_TENANTS },
          result: { data: { latestTenants: [] } },
        },
      ],
    });
    await screen.findByRole('status', { name: /no results/i });

    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
