# multistate-web

React 19 + TypeScript + Vite frontend for the UptimeCrew Multi-State project. Renders a tenant detail view backed (for now) by a stub fetch hook, and exercises React's "lift state up" pattern between two sibling components (`ThresholdSlider` writes, `ThresholdReadout` reads).

The Spring Boot backend lives at the repo root; see the [root README](../README.md) for the broader project context and the W4 D1 entry for what this app is for.

## Requirements

- Node.js 20+ (CI runs on `node-version: 20`)
- npm (uses the checked-in `package-lock.json`)

## Install

From this directory:

```bash
npm ci
```

## Scripts

| Command | What it does |
| --- | --- |
| `npm run dev` | Start the Vite dev server with HMR (default: `http://localhost:5173`). |
| `npm run build` | Type-check (`tsc --noEmit`) then produce a production build in `dist/`. |
| `npm run preview` | Serve the built `dist/` locally for a smoke check. |
| `npm run lint` | ESLint 9 flat config across the project. |
| `npm run typecheck` | TypeScript no-emit pass. |
| `npm test` | Run Vitest + Testing Library suite once (non-watch). |

## Project layout

```
multistate-web/
  src/
    main.tsx                       # React 19 root, mounts <App />
    App.tsx                        # renders <TenantDetailPage />
    pages/
      TenantDetailPage.tsx         # owns `threshold` state, fetches tenant via useTenant
    components/
      ThresholdSlider.tsx          # controlled input — writes threshold via onChange
      ThresholdReadout.tsx         # read-only display — reads threshold via value
    hooks/
      useTenant.ts                 # stub fetch hook returning { data, loading, error }
    types/
      tenant.ts                    # TenantViewModel + LineItem types
    test/
      TenantDetailPage.test.tsx    # Vitest + Testing Library: loading/error/data/threshold
  eslint.config.js                 # ESLint 9 flat config
  vite.config.ts                   # Vite (React plugin)
  vitest.config.ts                 # Vitest + jsdom environment
  tsconfig.json
```

## State pattern

`threshold` lives on `TenantDetailPage`, **not** on either child. `ThresholdSlider` calls `onChange` with the next value; `ThresholdReadout` receives the current value via `value`. Adding a third consumer (e.g. a histogram) means adding another sibling reading `threshold` from the page — neither slider nor readout changes.

## CI

[`/.github/workflows/web-ci.yml`](../.github/workflows/web-ci.yml) runs on PRs that touch `multistate-web/**`: `npm ci` → `lint` → `typecheck` → `test` → `build`. Keep the local script names in sync with the workflow.
