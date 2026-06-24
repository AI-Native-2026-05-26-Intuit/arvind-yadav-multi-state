import type { ReactElement } from 'react';
import {
  createBrowserRouter,
  Navigate,
  Outlet,
} from 'react-router-dom';
import { TenantListPage } from './pages/TenantListPage';
import { TenantDetailPage } from './pages/TenantDetailPage';
import { TenantSummaryPage } from './pages/TenantSummaryPage';
import { LoginPage } from './pages/LoginPage';

function ProtectedLayout(): ReactElement {
  // THREAT MODEL note: see src/apollo/client.ts — JWT-in-localStorage
  // is an XSS exposure we accept until W6 wires HttpOnly cookies.
  const jwt = localStorage.getItem('uc:jwt');
  if (jwt === null) return <Navigate to="/login" replace />;
  return <Outlet />;
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <ProtectedLayout />,
    children: [
      { path: '/tenants', element: <TenantListPage /> },
      { path: '/tenants/:id', element: <TenantDetailPage /> },
      { path: '/tenants/:id/summary', element: <TenantSummaryPage /> },
      { path: '/', element: <Navigate to="/tenants" replace /> },
    ],
  },
]);
