import { createBrowserRouter, Navigate } from 'react-router-dom';
import { TenantListPage } from './pages/TenantListPage';
import { TenantDetailPage } from './pages/TenantDetailPage';
import { TenantSummaryPage } from './pages/TenantSummaryPage';
import { LoginPage } from './pages/LoginPage';
import { ProtectedLayout } from './components/ProtectedLayout';

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
