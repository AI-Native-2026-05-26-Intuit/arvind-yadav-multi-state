import type { ReactElement } from 'react';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';

export function ThresholdSlider(): ReactElement {
  const threshold    = useTenantFilterStore((s) => s.threshold);
  const setThreshold = useTenantFilterStore((s) => s.setThreshold);

  return (
    <label>
      Threshold
      <input
        type="range"
        min={0}
        max={100}
        value={threshold}
        onChange={(e) => setThreshold(Number(e.currentTarget.value))}
      />
    </label>
  );
}
