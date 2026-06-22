import { useState } from 'react';
import type { ReactElement } from 'react';
import { useTenant } from '../hooks/useTenant';
import { ThresholdSlider } from '../components/ThresholdSlider';
import { ThresholdReadout } from '../components/ThresholdReadout';

export function TenantDetailPage(): ReactElement {
  // (1) `threshold` is owned HERE — the page is the source of truth.
  //     ThresholdSlider mutates it via the onChange prop; ThresholdReadout
  //     reads it via the value prop. Two siblings, one source.
  const [threshold, setThreshold] = useState<number>(50);

  const { data, loading, error } = useTenant('stub-id-1');

  if (loading)      return <p>Loading…</p>;
  if (error)        return <p>Failed to load: {error}</p>;
  if (data === null) return <p>Not found.</p>;

  return (
    <main>
      <h1>Tenant {data.id}</h1>
      <dl>
        <dt>primaryState</dt>    <dd>{data.primaryState}</dd>
        <dt>stateCount</dt>      <dd>{data.stateCount}</dd>
        <dt>totalAllocation</dt> <dd>{data.totalAllocation}</dd>
      </dl>

      <section>
        <ThresholdSlider  value={threshold} onChange={setThreshold} />
        <ThresholdReadout value={threshold} />
      </section>

      <section>
        <h2>Lines</h2>
        <ul>
          {data.lines.map((line) => (
            <li key={line.id}>{line.id}: {line.amount}</li>
          ))}
        </ul>
      </section>
    </main>
  );
}
