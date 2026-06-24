import type { ReactElement } from 'react';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';

export function ThresholdReadout(): ReactElement {
  const threshold = useTenantFilterStore((s) => s.threshold);
  return <span role="status">Threshold: {threshold}%</span>;
}
