import type { ReactElement } from 'react';
import { Navigate, Outlet } from 'react-router-dom';

export function ProtectedLayout(): ReactElement {
  // THREAT MODEL note: see src/apollo/client.ts — JWT-in-localStorage
  // is an XSS exposure we accept until W6 wires HttpOnly cookies.
  const jwt = localStorage.getItem('uc:jwt');
  if (jwt === null) return <Navigate to="/login" replace />;
  return <Outlet />;
}
