import { Link, Route, Routes } from 'react-router-dom';
import Navbar from './components/Navbar';
import ProtectedRoute from './components/ProtectedRoute';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import RoomsPage from './pages/RoomsPage';
import RoomDetailsPage from './pages/RoomDetailsPage';
import PaymentPage from './pages/PaymentPage';
import PaymentResultPage from './pages/PaymentResultPage';
import MyBookingsPage from './pages/MyBookingsPage';
import AdminDashboardPage from './pages/admin/AdminDashboardPage';
import AdminHotelsPage from './pages/admin/AdminHotelsPage';
import AdminRoomsPage from './pages/admin/AdminRoomsPage';
import AdminRoomFormPage from './pages/admin/AdminRoomFormPage';
import AdminPricingPage from './pages/admin/AdminPricingPage';
import AdminBookingsPage from './pages/admin/AdminBookingsPage';
import AdminUsersPage from './pages/admin/AdminUsersPage';

function MessagePage({ title, text }) {
  return (
    <div className="container section center">
      <h1>{title}</h1>
      <p className="muted">{text}</p>
      <Link className="btn btn-primary" to="/">Go home</Link>
    </div>
  );
}

export default function App() {
  return (
    <>
      <Navbar />
      <main>
        <Routes>
          {/* public */}
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/rooms" element={<RoomsPage />} />
          <Route path="/rooms/:roomId" element={<RoomDetailsPage />} />

          {/* guests (ROLE_USER) */}
          <Route element={<ProtectedRoute role="ROLE_USER" />}>
            <Route path="/my-bookings" element={<MyBookingsPage />} />
            <Route path="/bookings/:bookingId/pay" element={<PaymentPage />} />
            <Route path="/bookings/:bookingId/result" element={<PaymentResultPage />} />
          </Route>

          {/* administrators (ROLE_ADMIN) */}
          <Route element={<ProtectedRoute role="ROLE_ADMIN" />}>
            <Route path="/admin" element={<AdminDashboardPage />} />
            <Route path="/admin/hotels" element={<AdminHotelsPage />} />
            <Route path="/admin/rooms" element={<AdminRoomsPage />} />
            <Route path="/admin/rooms/new" element={<AdminRoomFormPage />} />
            <Route path="/admin/rooms/:roomId/edit" element={<AdminRoomFormPage />} />
            <Route path="/admin/pricing" element={<AdminPricingPage />} />
            <Route path="/admin/bookings" element={<AdminBookingsPage />} />
            <Route path="/admin/users" element={<AdminUsersPage />} />
          </Route>

          <Route path="/forbidden" element={<MessagePage title="Access denied" text="Your account does not have permission to open this page." />} />
          <Route path="*" element={<MessagePage title="Page not found" text="The page you are looking for does not exist." />} />
        </Routes>
      </main>
      <footer className="footer">
        <div className="container">GrandVista Hospitality · SOA &amp; Microservices university project · simulated payments only</div>
      </footer>
    </>
  );
}
