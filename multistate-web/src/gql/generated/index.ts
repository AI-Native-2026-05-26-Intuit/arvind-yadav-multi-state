// STUB: this file is overwritten by `pnpm codegen` once the GraphQL
// server at http://localhost:8080/graphql is reachable. Until then,
// it provides just enough typing for the call sites in src/pages/
// to compile. Do not hand-edit beyond what's needed to unblock
// typecheck — real types come from the schema.

import { gql } from '@apollo/client';
import { useQuery, useMutation } from '@apollo/client/react';
import type {
  QueryHookOptions,
  MutationHookOptions,
  MutationTuple,
  QueryResult,
} from '@apollo/client/react';

export type LatestTenantsQuery = {
  __typename?: 'Query';
  latestTenants: Array<{
    __typename?: 'Tenant';
    id: string;
    name: string;
    updatedAt: string;
  }>;
};

export type LatestTenantsQueryVariables = Record<string, never>;

const LATEST_TENANTS = gql`
  query LatestTenants {
    latestTenants(limit: 20) {
      id
      name
      updatedAt
    }
  }
`;

export function useLatestTenantsQuery(
  options?: QueryHookOptions<LatestTenantsQuery, LatestTenantsQueryVariables>,
): QueryResult<LatestTenantsQuery, LatestTenantsQueryVariables> {
  return useQuery<LatestTenantsQuery, LatestTenantsQueryVariables>(
    LATEST_TENANTS,
    options,
  );
}

export type SummarizeTenantMutation = {
  __typename?: 'Mutation';
  summarizeTenant: {
    __typename: 'TenantSummary';
    id: string;
    summaryText: string;
    confidence: string;
  };
};

export type SummarizeTenantMutationVariables = { id: string };

const SUMMARIZE_TENANT = gql`
  mutation SummarizeTenant($id: ID!) {
    summarizeTenant(id: $id) {
      id
      summaryText
      confidence
      __typename
    }
  }
`;

export function useSummarizeTenantMutation(
  options?: MutationHookOptions<
    SummarizeTenantMutation,
    SummarizeTenantMutationVariables
  >,
): MutationTuple<SummarizeTenantMutation, SummarizeTenantMutationVariables> {
  return useMutation<SummarizeTenantMutation, SummarizeTenantMutationVariables>(
    SUMMARIZE_TENANT,
    options,
  );
}
