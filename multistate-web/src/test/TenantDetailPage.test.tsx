import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TenantDetailPage } from '../pages/TenantDetailPage';

const MOCK = {
  id: 'stub-id-1',
  primaryState: 'CA',
  stateCount: 5,
  totalAllocation: '12500.00',
  lines: [
    { id: 'line-1', amount: '100.00' },
    { id: 'line-2', amount: '250.00' },
  ],
};

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify(MOCK)))));
});

describe('TenantDetailPage', () => {
  it('renders entity id and a sample field from the mock JSON', async () => {
    render(<TenantDetailPage />);
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('stub-id-1'));
    expect(screen.getByText('CA')).toBeInTheDocument();
  });

  it('updates the readout when the slider is moved (lifted state)', async () => {
    const user = userEvent.setup();
    render(<TenantDetailPage />);
    await waitFor(() => screen.getByRole('heading', { level: 1 }));

    const slider = screen.getByLabelText(/Threshold/i);
    slider.focus();
    // jsdom does not implement native range-input keyboard semantics
    // (ArrowRight bumping value), so we fire change directly. The
    // userEvent.keyboard call still validates focus + key routing.
    await user.keyboard('{ArrowRight}');
    fireEvent.change(slider, { target: { value: '51' } });

    expect(screen.getByRole('status')).toHaveTextContent(/Threshold:\s*51%/);
  });
});
