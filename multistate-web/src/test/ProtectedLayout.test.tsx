import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  MemoryRouter,
  Navigate,
  Outlet,
  Route,
  Routes,
} from 'react-router-dom';
import type { ReactElement } from 'react';

function ProtectedLayout(): ReactElement {
  const jwt = localStorage.getItem('uc:jwt');
  if (jwt === null) return <Navigate to="/login" replace />;
  return <Outlet />;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<p>login page</p>} />
        <Route element={<ProtectedLayout />}>
          <Route path="/tenants" element={<p>tenants page</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('ProtectedLayout', () => {
  it('redirects to /login when uc:jwt is missing', () => {
    renderAt('/tenants');
    expect(screen.getByText('login page')).toBeInTheDocument();
    expect(screen.queryByText('tenants page')).not.toBeInTheDocument();
  });

  it('renders the outlet when uc:jwt is present', () => {
    localStorage.setItem('uc:jwt', 'dev.fake.jwt');
    renderAt('/tenants');
    expect(screen.getByText('tenants page')).toBeInTheDocument();
    expect(screen.queryByText('login page')).not.toBeInTheDocument();
  });
});
