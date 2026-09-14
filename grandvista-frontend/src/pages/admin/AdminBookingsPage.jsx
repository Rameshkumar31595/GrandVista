import { useCallback, useEffect, useState } from 'react';
import { bookingApi } from '../../api/endpoints';
import { Alert, Loading, StatusBadge, formatDate, formatMoney } from '../../components/common';

export default function AdminBookingsPage() {
  const [status, setStatus] = useState('');
  const [bookings, setBookings] = useState(null);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);

  const load = useCallback(() => {
    bookingApi.all(status || undefined).then(setBookings).catch((err) => setError(err.message));
  }, [status]);
  useEffect(() => { load(); }, [load]);

  const cancel = async (booking) => {
    if (!window.confirm(`Cancel booking #${booking.bookingId} for user ${booking.userId}?`)) return;
    setError(null);
    try {
      const updated = await bookingApi.cancel(booking.bookingId);
      setMessage(`Booking #${updated.bookingId}: ${updated.statusReason}`);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div className="container section">
      <h1>All bookings</h1>
      <Alert type="success" onClose={() => setMessage(null)}>{message}</Alert>
      <Alert>{error}</Alert>
      <label className="inline-filter">Status
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All</option><option>PENDING</option><option>CONFIRMED</option><option>CANCELLED</option>
        </select>
      </label>

      {!bookings ? <Loading /> : (
        <div className="table-wrap card">
          <table className="table">
            <thead><tr><th>#</th><th>User</th><th>Hotel / room</th><th>Stay</th><th>Guests</th><th>Amount</th><th>Status</th><th>Payment ref</th><th /></tr></thead>
            <tbody>
              {bookings.map((booking) => (
                <tr key={booking.bookingId}>
                  <td>{booking.bookingId}</td>
                  <td>{booking.userId}</td>
                  <td>{booking.hotelName}<br /><small className="muted">Room {booking.roomNumber} · {booking.roomType}</small></td>
                  <td>{formatDate(booking.checkIn)} → {formatDate(booking.checkOut)}</td>
                  <td>{booking.numberOfGuests}</td>
                  <td>{formatMoney(booking.totalAmount)}</td>
                  <td><StatusBadge status={booking.bookingStatus} /><br /><small className="muted">{booking.statusReason}</small></td>
                  <td><code>{booking.paymentReference || '—'}</code></td>
                  <td>{booking.bookingStatus !== 'CANCELLED' && (
                    <button className="btn btn-danger btn-sm" type="button" onClick={() => cancel(booking)}>Cancel</button>
                  )}</td>
                </tr>
              ))}
              {bookings.length === 0 && <tr><td colSpan="9" className="muted center">No bookings.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
