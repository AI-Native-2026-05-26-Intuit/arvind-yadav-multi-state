// jest-axe ships without bundled types as of v10. The package's matcher is
// only consumed inside setupTests.ts; this ambient declaration lets tsc see
// the named export without pulling in @types/jest-axe (which targets jest,
// not vitest).
declare module 'jest-axe' {
  type MatcherResult = { pass: boolean; message: () => string };
  type AxeMatcher = (received: unknown) => MatcherResult | Promise<MatcherResult>;
  // Index signature so vitest's MatchersObject (Record<string, ...>) accepts it.
  export const toHaveNoViolations: { [k: string]: AxeMatcher };
  export function axe(node: Element | Document, options?: unknown): Promise<unknown>;
  export function configureAxe(options?: unknown): typeof axe;
}
