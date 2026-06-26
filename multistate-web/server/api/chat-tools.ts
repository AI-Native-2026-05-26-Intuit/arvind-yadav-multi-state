import { tool } from 'ai';
import { z } from 'zod';

const REST_BASE = 'http://localhost:8080/api/v1';

export const tenantTools = {
  lookupTenant: tool({
    description:
      'Look up a single tenant by id. ' +
      'Returns the canonical record stored in the W3 D2 REST backend.',
    parameters: z.object({
      id: z.string(),
    }),
    execute: async ({ id }) => {
      const res = await fetch(`${REST_BASE}/tenants/${id}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return (await res.json()) as unknown;
    },
  }),
  nexusForState: tool({
    description:
      'Search the tenant corpus by state. ' +
      'Returns a small array the assistant can quote inline.',
    parameters: z.object({
      state: z.string(),
    }),
    execute: async ({ state }) => {
      const res = await fetch(
        `${REST_BASE}/tenants?state=${encodeURIComponent(state)}`,
      );
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return (await res.json()) as unknown;
    },
  }),
};
