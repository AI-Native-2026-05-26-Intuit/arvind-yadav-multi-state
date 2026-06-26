import type { ReactElement } from 'react';
import { useMemo } from 'react';
import { useLatestTenantsQuery } from '../gql/generated';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';

export function TenantListPage(): ReactElement {
  const { loading, error, data } = useLatestTenantsQuery();
  const searchText = useTenantFilterStore((s) => s.searchText);
  const setSearchText = useTenantFilterStore((s) => s.setSearchText);

  const rows = useMemo(() => {
    const all = data?.latestTenants ?? [];
    const q = searchText.trim().toLowerCase();
    if (!q) return all;
    return all.filter((r) => (r.name ?? '').toLowerCase().includes(q));
  }, [data, searchText]);

  return (
    <section>
      <h1>Tenants</h1>
      <label>
        Search
        <input
          type="search"
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
        />
      </label>
      {loading ? (
        <div role="status" aria-label="loading…">loading…</div>
      ) : error ? (
        <div role="alert">Error: {error.message}</div>
      ) : rows.length === 0 ? (
        <div role="status" aria-label="no results">no results</div>
      ) : (
        <ul aria-label="tenant-list">
          {rows.map((r) => (
            <li key={r.id}>
              <a href={`/tenants/${r.id}`}>{r.name}</a>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
