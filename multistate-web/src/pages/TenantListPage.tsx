import type { ReactElement } from 'react';
import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLatestTenantsQuery } from '../gql/generated';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';

export function TenantListPage(): ReactElement {
  const { loading, error, data } = useLatestTenantsQuery();
  const searchText = useTenantFilterStore((s) => s.searchText);
  const setSearchText = useTenantFilterStore((s) => s.setSearchText);
  const navigate = useNavigate();

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
        <table aria-label="tenant-list">
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Updated</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr
                key={r.id}
                onClick={() => void navigate(`/tenants/${r.id}/chat`)}
                style={{ cursor: 'pointer' }}
              >
                <td>{r.name}</td>
                <td>{r.updatedAt}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
