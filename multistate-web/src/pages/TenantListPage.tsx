import type { ReactElement } from 'react';
import { useLatestTenantsQuery } from '../gql/generated';

export function TenantListPage(): ReactElement {
  const { loading, error, data } = useLatestTenantsQuery();

  if (loading) {
    return <div role="status">Loading...</div>;
  }
  if (error) {
    return <div role="alert">Error: {error.message}</div>;
  }

  const rows = data?.latestTenants ?? [];
  if (rows.length === 0) {
    return <p>No tenants yet.</p>;
  }

  return (
    <ul aria-label="tenant-list">
      {rows.map((r) => (
        <li key={r.id}>
          <a href={`/tenants/${r.id}`}>{r.name}</a>
        </li>
      ))}
    </ul>
  );
}
