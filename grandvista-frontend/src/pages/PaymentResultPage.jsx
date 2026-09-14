import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { bookingApi } from '../api/endpoints';
import { Alert, Loading, StatusBadge, formatDate, formatMoney } from '../components/common';

export default function PaymentResultPage() {
  const { bookingId } = useParams();
  const location = useLocation();
  const [outcome, setOutcome] = useState(location.state?.outcome || null);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!outcome) {
      // Page was reloaded: rebuild the result from the stored booking.
      bookingApi.get(bookingId)
        .then((booking) => setOutcome({ booking, paymentStatus: null, message: booking.statusReason }))
        .catch((err) => setError(err.message));
    }
  }, [bookingId, outcome]);

  if (!outcome) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;

  const { booking } = outcome;
  const confirmed = booking.bookingStatus === 'CONFIRMED';

  return (
    <div className="container section narrow">
      <div className={`card result-card ${confirmed ? 'success' : 'failure'}`}>
        <div className="result-icon">{confirmed ? '✓' : '✕'}</div>
        <h1>{confirmed ? 'Booking confirmed' : 'Booking not confirmed'}</h1>
        <p>{outcome.message}</p>

        <ul className="facts">
          <li><strong>Booking #</strong>{booking.bookingId} <StatusBadge status={booking.bookingStatus} /></li>
          {outcome.paymentStatus && <li><strong>Payment:</strong> <StatusBadge status={outcome.paymentStatus} /></li>}
          {booking.paymentReference && <li><strong>Transaction reference:</strong> <code>{booking.paymentReference}</code></li>}
          <li><strong>Hotel:</strong> {booking.hotelName}, room {booking.roomNumber}</li>
          <li><strong>Stay:</strong> {formatDate(booking.checkIn)} → {formatDate(booking.checkOut)}</li>
          <li><strong>Amount:</strong> {formatMoney(booking.totalAmount)}</li>
          {!confirmed && <li><strong>Reason:</strong> {booking.statusReason}</li>}
        </ul>

        <div className="actions center">
          <Link className="btn btn-primary" to="/my-bookings">My bookings</Link>
          {!confirmed && <Link className="btn btn-outline" to={`/rooms/${booking.roomId}`}>Try again</Link>}
        </div>
      </div>
    </div>
  );
}
