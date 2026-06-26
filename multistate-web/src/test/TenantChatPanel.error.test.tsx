import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { TenantChatPanel } from '../pages/TenantChatPanel';
import { server } from './server';

function renderPanel(): void {
  render(
    <MemoryRouter initialEntries={['/tenants/stub-id/chat']}>
      <Routes>
        <Route path="/tenants/:id/chat" element={<TenantChatPanel />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('TenantChatPanel error path', () => {
  it('renders a role="alert" pane when /api/chat returns 500', async () => {
    server.use(
      http.post('/api/chat', () =>
        new HttpResponse('upstream exploded', { status: 500 }),
      ),
    );

    const user = userEvent.setup();
    renderPanel();
    await user.type(screen.getByLabelText('chat-input'), 'hello');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    const alert = await waitFor(() => screen.getByRole('alert'));
    expect(alert).toBeInTheDocument();
    expect(alert.textContent).toMatch(/Error:/);
  });

  it('clears the loading spinner after an upstream error', async () => {
    server.use(
      http.post('/api/chat', () =>
        new HttpResponse('upstream exploded', { status: 500 }),
      ),
    );

    const user = userEvent.setup();
    renderPanel();
    await user.type(screen.getByLabelText('chat-input'), 'hello');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => screen.getByRole('alert'));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
