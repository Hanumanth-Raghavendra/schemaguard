import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { useAuth } from './AuthContext.ts';

/** Shows its content to a logged-in account; anyone else is sent to log in and brought back. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'checking') return <p role="status">Checking your session…</p>;
  if (status === 'anonymous') return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  return children;
}
