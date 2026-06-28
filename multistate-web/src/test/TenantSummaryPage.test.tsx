import { describe, it, expect } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { gql } from '@apollo/client';
import { axe } from 'jest-axe';
import { renderWithProviders } from './renderWithProviders';
import { TenantSummaryPage } from '../pages/TenantSummaryPage';

const SUMMARIZE = gql`
  mutation SummarizeTenant($id: ID!) {
    summarizeTenant(id: $id) {
      id
      summaryText
      confidence
      __typename
    }
  }
`;

const successMocks = [
  {
    request: { query: SUMMARIZE, variables: { id: 'stub-1' } },
    delay: 50,
    result: {
      data: {
        summarizeTenant: {
          __typename: 'TenantSummary',
          id: 'stub-1',
          summaryText: 'stub summary from MSW',
          confidence: 'HIGH',
        },
      },
    },
  },
];

const errorMocks = [
  {
    request: { query: SUMMARIZE, variables: { id: 'stub-1' } },
    result: { errors: [{ message: 'Tenant gone' }] },
  },
];

const networkErrorMocks = [
  {
    request: { query: SUMMARIZE, variables: { id: 'stub-1' } },
    error: new Error('Network kaboom'),
  },
];

function mountSummaryPage(mocks: ReadonlyArray<unknown>) {
  return renderWithProviders(
    <Routes>
      <Route path="/tenants/:id/summary" element={<TenantSummaryPage />} />
    </Routes>,
    {
      apolloMocks: mocks as never,
      initialEntries: ['/tenants/stub-1/summary'],
    },
  );
}

describe('TenantSummaryPage', () => {
  it('renders the page heading on initial mount', () => {
    mountSummaryPage(successMocks);
    expect(screen.getByRole('heading', { name: /tenant summary/i })).toBeInTheDocument();
  });

  it('starts in the empty state until the user requests a summary', () => {
    mountSummaryPage(successMocks);
    expect(screen.getByRole('status', { name: /no results/i })).toBeInTheDocument();
  });

  it('shows the loading status while the mutation is in flight', async () => {
    const { user } = mountSummaryPage(successMocks);
    await user.click(screen.getByRole('button', { name: /summarize/i }));
    expect(await screen.findByRole('status', { name: /loading…/i })).toBeInTheDocument();
  });

  it('renders the resolved summary after Summarize is clicked', async () => {
    const { user } = mountSummaryPage(successMocks);
    await user.click(screen.getByRole('button', { name: /summarize/i }));

    await waitFor(() => {
      expect(screen.getByText('stub summary from MSW')).toBeInTheDocument();
    });
    expect(screen.getByText(/confidence:\s*HIGH/)).toBeInTheDocument();
  });

  it('surfaces a GraphQL mutation error through role=alert', async () => {
    const { user } = mountSummaryPage(errorMocks);
    await user.click(screen.getByRole('button', { name: /summarize/i }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/tenant gone/i);
  });

  it('surfaces a network error from the mutation through role=alert', async () => {
    const { user } = mountSummaryPage(networkErrorMocks);
    await user.click(screen.getByRole('button', { name: /summarize/i }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/network kaboom/i);
  });

  it('has no axe-detectable a11y violations on the initial render', async () => {
    const { container } = mountSummaryPage(successMocks);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
