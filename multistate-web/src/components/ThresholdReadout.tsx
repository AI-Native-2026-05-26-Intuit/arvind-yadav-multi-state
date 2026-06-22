import type { ReactElement } from 'react';

type Props = { readonly value: number };

export function ThresholdReadout({ value }: Props): ReactElement {
  return <span role="status">Threshold: {value}%</span>;
}
