import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { bookingApi } from '../api/endpoints';
import { Alert, EmptyState, Loading, StatusBadge, formatDate, formatDateTime, formatMoney, isoDate } from '../components/common';
import { canGuestCancel, isInFlight } from '../utils/bookingStatus';
import { usePolling } from '../utils/usePolling';

export default function MyBookingsPage() {
  const [bookings, setBookings] = useState(null);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);

  const load = useCallback(() => {
    bookingApi.mine().then(setBookings).catch((err) => setError(err.message));
  }, []);

  useEffect(load, [load]);
  // Bookings in an intermediate state settle on their own: keep refreshing until none is left.
  usePolling(load, Boolean(bookings?.some((booking) => isInFlight(booking.bookingStatus))));

  const cancel = async (booking) => {
    const refundNote = booking.bookingStatus === 'CONFIRMED' ? ' Your payment will be refunded.' : '';
    if (!window.confirm(`Cancel booking #${booking.bookingId}?${refundNote}`)) return;
    setError(null);
    try {
      const updated = await bookingApi.cancel(booking.bookingId);
      setMessage(`Booking #${updated.bookingId} cancelled: ${updated.statusReason}`);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  if (!bookings) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;

  return (
    <div className="container section">
      <h1>My bookings</h1>
      <Alert type="success" onClose={() => setMessage(null)}>{message}</Alert>
      <Alert>{error}</Alert>

      {bookings.length === 0 ? (
        <EmptyState title="No bookings yet"><Link className="btn btn-primary" to="/rooms">Find a room</Link></EmptyState>
      ) : (
        <div className="booking-list">
          {bookings.map((booking) => {
            const cancellable = canGuestCancel(booking, isoDate(0));
            return (
              <article key={booking.bookingId} className="card booking-item">
                <div className="booking-main">
                  <div className="summary-head">
                    <h3>{booking.hotelName} · Room {booking.roomNumber}</h3>
                    <StatusBadge status={booking.bookingStatus} />
                  </div>
                  <p className="muted">
                    #{booking.bookingId} · {formatDate(booking.checkIn)} → {formatDate(booking.checkOut)} · {booking.nights} night(s) · {booking.numberOfGuests} guest(s)
                  </p>
                  <p className="small">{booking.statusReason}</p>
                  {booking.paymentReference && <p className="small">Payment ref: <code>{booking.paymentReference}</code></p>}
                  {booking.bookingStatus === 'PENDING' && (
                    <p className="small warning-text">Pay before {formatDateTime(booking.paymentDueBy)}</p>
                  )}
                  {isInFlight(booking.bookingStatus) && (
                    <p className="small warning-text">This booking is being updated - the status refreshes automatically.</p>
                  )}
                </div>
                <div className="booking-side">
                  <span className="price">{formatMoney(booking.totalAmount)}</span>
                  {booking.bookingStatus === 'PENDING' && (
                    <Link className="btn btn-gold btn-sm" to={`/bookings/${booking.bookingId}/pay`}>Pay now</Link>
                  )}
                  {cancellable && (
                    <button className="btn btn-outline btn-sm" type="button" onClick={() => cancel(booking)}>Cancel</button>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
