import type { ReactElement } from 'react';
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';

type TenantRow = { readonly id: string; readonly name: string; readonly updatedAt: string };

async function fetchTenants(): Promise<readonly TenantRow[]> {
  const res = await fetch('/api/v1/tenants');
  if (!res.ok) throw new Error(`Failed to load tenants: HTTP ${res.status}`);
  return (await res.json()) as readonly TenantRow[];
}

export function TenantTable(): ReactElement {
  const { data, isLoading, error } = useQuery({
    queryKey: ['tenants', 'list'],
    queryFn: fetchTenants,
  });
  const searchText = useTenantFilterStore((s) => s.searchText);
  const setSearchText = useTenantFilterStore((s) => s.setSearchText);

  const rows = useMemo(() => {
    const all = data ?? [];
    const q = searchText.trim().toLowerCase();
    if (!q) return all;
    return all.filter((r) => r.name.toLowerCase().includes(q));
  }, [data, searchText]);

  return (
    <section>
      <label>
        Filter
        <input
          type="search"
          role="searchbox"
          aria-label="filter"
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
        />
      </label>
      {isLoading && <div role="status" aria-label="loading…">loading…</div>}
      {error && <div role="alert">{(error as Error).message}</div>}
      {!isLoading && !error && (
        <table>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Updated</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
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
