import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MockedProvider } from '@apollo/client/testing/react';
import { MemoryRouter } from 'react-router-dom';
import { gql } from '@apollo/client';
import { TenantListPage } from '../pages/TenantListPage';

// NOTE: Apollo + MSW + jsdom + Node's intrinsic undici don't compose — jsdom
// installs its own AbortSignal class onto globalThis, undici (used by fetch
// inside Apollo's HttpLink) rejects it as "not an instance of AbortSignal".
// MSW handlers in src/test/handlers.ts still cover this query for any future
// integration test that uses a Node-only env; the component-level test here
// uses Apollo's official MockedProvider, which short-circuits the fetch path.
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
          { __typename: 'Tenant', id: 'stub-1', name: 'stub one',   updatedAt: '2025-01-01T00:00:00Z' },
          { __typename: 'Tenant', id: 'stub-2', name: 'stub two',   updatedAt: '2025-01-02T00:00:00Z' },
          { __typename: 'Tenant', id: 'stub-3', name: 'stub three', updatedAt: '2025-01-03T00:00:00Z' },
        ],
      },
    },
  },
];

describe('TenantListPage', () => {
  it('renders three rows once the GraphQL mock resolves', async () => {
    render(
      <MockedProvider mocks={successMocks}>
        <MemoryRouter>
          <TenantListPage />
        </MemoryRouter>
      </MockedProvider>,
    );

    await waitFor(() =>
      expect(screen.getAllByRole('listitem')).toHaveLength(3),
    );
    expect(screen.getByText('stub one')).toBeInTheDocument();
  });

  it('renders the loading spinner before data resolves', () => {
    render(
      <MockedProvider mocks={successMocks}>
        <MemoryRouter>
          <TenantListPage />
        </MemoryRouter>
      </MockedProvider>,
    );
    expect(screen.getByRole('status')).toBeInTheDocument();
  });
});
