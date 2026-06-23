import type { ReactElement } from 'react';
import { useTenant } from '../hooks/useTenant';
import { ThresholdSlider } from '../components/ThresholdSlider';
import { ThresholdReadout } from '../components/ThresholdReadout';
import { FilterStrip } from '../components/FilterStrip';

export function TenantDetailPage(): ReactElement {
  const { data, loading, error } = useTenant('stub-id-1');

  return (
    <>
      <FilterStrip />
      <DetailCard loading={loading} error={error} data={data} />
    </>
  );
}

function DetailCard({
  loading,
  error,
  data,
}: {
  readonly loading: boolean;
  readonly error:   string | null;
  readonly data:    ReturnType<typeof useTenant>['data'];
}): ReactElement {
  if (loading)       return <p>Loading…</p>;
  if (error !== null) return <p>Failed to load: {error}</p>;
  if (data === null)  return <p>Not found.</p>;

  return (
    <main>
      <h1>Tenant {data.id}</h1>
      <dl>
        <dt>primaryState</dt>    <dd>{data.primaryState}</dd>
        <dt>stateCount</dt>      <dd>{data.stateCount}</dd>
        <dt>totalAllocation</dt> <dd>{data.totalAllocation}</dd>
      </dl>

      <section>
        <ThresholdSlider />
        <ThresholdReadout />
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
