import { useQuery } from '@tanstack/react-query';

export type TenantRest = {
  readonly id: string;
  readonly name: string;
  readonly updatedAt: string;
};

export function useGetMultiStateRest(id: string) {
  return useQuery({
    queryKey: ['multistate', id],
    enabled: Boolean(id),
    queryFn: async () => {
      const res = await fetch(`http://localhost:8080/api/v1/tenants/${id}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return (await res.json()) as TenantRest;
    },
  });
}
