import { graphql, http, HttpResponse } from 'msw';
import { sseHandlers } from './sse-handlers';

// Existing happy-path stubs used by the component tests in W4 D5 Task 1.
// Integration tests in Task 2 layer on top of these via `tenantHandlers`
// (collection endpoint + Apollo) and opt into the error branch via
// `server.use(tenantErrorHandler)`.
export const handlers = [
  ...sseHandlers,
  graphql.query('LatestTenants', () =>
    HttpResponse.json({
      data: {
        latestTenants: [
          { id: 'stub-1', name: 'stub one',   updatedAt: '2025-01-01T00:00:00Z', __typename: 'Tenant' },
          { id: 'stub-2', name: 'stub two',   updatedAt: '2025-01-02T00:00:00Z', __typename: 'Tenant' },
          { id: 'stub-3', name: 'stub three', updatedAt: '2025-01-03T00:00:00Z', __typename: 'Tenant' },
        ],
      },
    }),
  ),
  graphql.mutation('SummarizeTenant', ({ variables }) =>
    HttpResponse.json({
      data: {
        summarizeTenant: {
          __typename: 'TenantSummary',
          id: String(variables.id),
          summaryText: 'stub summary from MSW',
          confidence: 'HIGH',
        },
      },
    }),
  ),
  http.get('http://localhost:8080/api/v1/tenants/:id', ({ params }) =>
    HttpResponse.json({
      id:        String(params.id),
      name:      'stub tenant',
      updatedAt: '2025-01-04T00:00:00Z',
    }),
  ),
];

// Integration handlers (W4 D5 Task 2). Kept as a separate export so tests
// can call `server.use(...tenantHandlers)` to install them, then opt into
// the 500 branch via `server.use(tenantErrorHandler)`.
export const tenantHandlers = [
  // ---- Apollo happy path ----
  graphql.query('LatestTenants', () =>
    HttpResponse.json({
      data: {
        latestTenants: [
          { __typename: 'Tenant', id: 'ten_synth_a1b2', name: 'Stub Tenant 01', updatedAt: '2025-01-01T00:00:00Z' },
          { __typename: 'Tenant', id: 'ten_synth_c3d4', name: 'Stub Tenant 02', updatedAt: '2025-01-02T00:00:00Z' },
        ],
      },
    }),
  ),

  // ---- TanStack REST happy path ----
  // Match both same-origin (`/api/v1/tenants`) and the proxied
  // `localhost:8080` form so either base URL hits the same stub.
  http.get('/api/v1/tenants', () =>
    HttpResponse.json([
      { id: 'ten_synth_a1b2', name: 'Stub Tenant 01', updatedAt: '2025-01-01T00:00:00Z' },
      { id: 'ten_synth_c3d4', name: 'Stub Tenant 02', updatedAt: '2025-01-02T00:00:00Z' },
    ]),
  ),
  http.get('http://localhost:8080/api/v1/tenants', () =>
    HttpResponse.json([
      { id: 'ten_synth_a1b2', name: 'Stub Tenant 01', updatedAt: '2025-01-01T00:00:00Z' },
      { id: 'ten_synth_c3d4', name: 'Stub Tenant 02', updatedAt: '2025-01-02T00:00:00Z' },
    ]),
  ),

  // Apollo loading variant — never resolves, so the role=status skeleton
  // stays in the DOM for as long as the test cares to look.
  // (Tests opt in via `server.use(apolloLoadingHandler)`.)
];

export const tenantErrorHandler = http.get(
  '/api/v1/tenants',
  () => HttpResponse.json({ error: 'boom' }, { status: 500 }),
);

export const tenantErrorHandlerProxied = http.get(
  'http://localhost:8080/api/v1/tenants',
  () => HttpResponse.json({ error: 'boom' }, { status: 500 }),
);

// Apollo loading handler: returns a Promise that never settles so the
// component stays in its loading branch for the duration of the test.
// A never-settling Promise keeps Apollo in its loading branch for the
// duration of the test. The cast is needed because msw's resolver return
// type expects an actual response — `never` is assignable to anything but
// TS can't widen `Promise<never>` automatically here.
export const apolloLoadingHandler = graphql.query(
  'LatestTenants',
  (): Promise<never> => new Promise<never>(() => undefined),
);

// Apollo error handler: returns a GraphQL errors[] envelope.
export const apolloErrorHandler = graphql.query('LatestTenants', () =>
  HttpResponse.json({ errors: [{ message: 'apollo boom' }] }),
);
