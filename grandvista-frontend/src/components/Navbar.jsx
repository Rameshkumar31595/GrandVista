import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Navbar() {
  const { user, isAuthenticated, isAdmin, isGuest, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/');
  };

  return (
    <header className="navbar">
      <div className="container navbar-inner">
        <NavLink to="/" className="brand">
          <span className="brand-mark">GV</span>
          <span>GrandVista <small>Hospitality</small></span>
        </NavLink>

        <nav className="nav-links">
          <NavLink to="/rooms">Rooms</NavLink>
          {isGuest && <NavLink to="/my-bookings">My Bookings</NavLink>}
          {isAdmin && (
            <>
              <NavLink to="/admin" end>Dashboard</NavLink>
              <NavLink to="/admin/hotels">Hotels</NavLink>
              <NavLink to="/admin/rooms">Rooms Admin</NavLink>
              <NavLink to="/admin/pricing">Pricing</NavLink>
              <NavLink to="/admin/bookings">Bookings</NavLink>
              <NavLink to="/admin/users">Users</NavLink>
            </>
          )}
        </nav>

        <div className="nav-auth">
          {isAuthenticated ? (
            <>
              <span className="nav-user" title={user.email}>
                {user.name} <span className={`role-chip ${isAdmin ? 'admin' : ''}`}>{isAdmin ? 'ADMIN' : 'GUEST'}</span>
              </span>
              <button type="button" className="btn btn-outline btn-sm" onClick={handleLogout}>Logout</button>
            </>
          ) : (
            <>
              <NavLink to="/login" className="btn btn-outline btn-sm">Login</NavLink>
              <NavLink to="/register" className="btn btn-primary btn-sm">Register</NavLink>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
