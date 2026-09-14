import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

/**
 * UX-only route guard. It hides pages the user cannot use, but the REAL authorization is enforced
 * by the API Gateway and every microservice - a user who bypasses this still gets 401/403 from the API.
 */
export default function ProtectedRoute({ role }) {
  const { isAuthenticated, user } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  if (role && user.role !== role) {
    return <Navigate to="/forbidden" replace />;
  }
  return <Outlet />;
}
