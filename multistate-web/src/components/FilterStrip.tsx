import type { ReactElement } from 'react';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';

// Known US state codes used for the chip filter. Kept inline because the
// list is short and stable; promote to a shared module if it grows.
const STATE_CODES = ['CA', 'NY', 'TX', 'WA', 'IL'] as const;

function StateChips(): ReactElement {
  const stateFilter    = useTenantFilterStore((s) => s.stateFilter);
  const setStateFilter = useTenantFilterStore((s) => s.setStateFilter);

  const toggle = (code: string): void => {
    setStateFilter(
      stateFilter.includes(code)
        ? stateFilter.filter((c) => c !== code)
        : [...stateFilter, code],
    );
  };

  return (
    <fieldset>
      <legend>States</legend>
      {STATE_CODES.map((code) => (
        <label key={code}>
          <input
            type="checkbox"
            checked={stateFilter.includes(code)}
            onChange={() => toggle(code)}
          />
          {code}
        </label>
      ))}
    </fieldset>
  );
}

function SearchBox(): ReactElement {
  const searchText    = useTenantFilterStore((s) => s.searchText);
  const setSearchText = useTenantFilterStore((s) => s.setSearchText);

  return (
    <label>
      Search
      <input
        type="search"
        value={searchText}
        onChange={(e) => setSearchText(e.currentTarget.value)}
      />
    </label>
  );
}

function ArchivedToggle(): ReactElement {
  const includeArchived    = useTenantFilterStore((s) => s.includeArchived);
  const setIncludeArchived = useTenantFilterStore((s) => s.setIncludeArchived);

  return (
    <label>
      <input
        type="checkbox"
        checked={includeArchived}
        onChange={(e) => setIncludeArchived(e.currentTarget.checked)}
      />
      Include archived
    </label>
  );
}

function ResetButton(): ReactElement {
  const reset = useTenantFilterStore((s) => s.reset);
  return <button type="button" onClick={reset}>Reset filters</button>;
}

export function FilterStrip(): ReactElement {
  return (
    <section aria-label="Filters">
      <StateChips />
      <SearchBox />
      <ArchivedToggle />
      <ResetButton />
    </section>
  );
}
