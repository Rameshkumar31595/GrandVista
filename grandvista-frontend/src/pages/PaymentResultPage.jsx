import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { bookingApi } from '../api/endpoints';
import { Alert, Loading, StatusBadge, formatDate, formatMoney } from '../components/common';
import { isInFlight } from '../utils/bookingStatus';
import { usePolling } from '../utils/usePolling';

export default function PaymentResultPage() {
  const { bookingId } = useParams();
  const location = useLocation();
  const [outcome, setOutcome] = useState(location.state?.outcome || null);
  const [error, setError] = useState(null);

  const refresh = useCallback(() => {
    bookingApi.get(bookingId)
      .then((booking) => setOutcome((previous) => ({
        ...previous,
        booking,
        // "UNKNOWN" was only true until the server verified the payment; show what it settled on.
        paymentStatus: booking.bookingStatus === 'CONFIRMED' ? 'SUCCESS'
          : previous?.paymentStatus === 'UNKNOWN' ? null : previous?.paymentStatus ?? null,
      })))
      .catch((err) => setError(err.message));
  }, [bookingId]);

  useEffect(() => {
    if (!outcome) {
      // Page was reloaded: rebuild the result from the stored booking.
      bookingApi.get(bookingId)
        .then((booking) => setOutcome({ booking, paymentStatus: null, message: booking.statusReason }))
        .catch((err) => setError(err.message));
    }
  }, [bookingId, outcome]);

  const processing = Boolean(outcome) && isInFlight(outcome.booking.bookingStatus);
  // The Payment Service did not answer in time (HTTP 202): the server verifies the payment itself and moves the
  // booking on; keep asking until it does.
  usePolling(refresh, processing);

  if (!outcome) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;

  const { booking } = outcome;
  const confirmed = booking.bookingStatus === 'CONFIRMED';
  const unpaid = booking.bookingStatus === 'PENDING';
  const kind = confirmed ? 'success' : processing ? 'processing' : 'failure';

  let heading = 'Booking not confirmed';
  let text = outcome.message;
  if (confirmed) {
    heading = 'Booking confirmed';
    if (!text || processing) text = 'Payment successful. Your booking is confirmed.';
  } else if (processing) {
    heading = booking.bookingStatus === 'PAYMENT_IN_PROGRESS' ? 'Verifying your payment' : 'Finishing your cancellation';
  } else if (unpaid) {
    heading = 'Payment not completed';
    text = 'No payment was taken. Your room is still held - you can pay again before the time runs out.';
  } else if (!text) {
    text = booking.statusReason;
  }

  return (
    <div className="container section narrow">
      <div className={`card result-card ${kind}`}>
        <div className="result-icon">{confirmed ? '✓' : processing ? '…' : '✕'}</div>
        <h1>{heading}</h1>
        <p>{text}</p>
        {processing && <p className="small muted">This page updates automatically. Please do not pay again.</p>}

        <ul className="facts">
          <li><strong>Booking #</strong>{booking.bookingId} <StatusBadge status={booking.bookingStatus} /></li>
          {outcome.paymentStatus && <li><strong>Payment:</strong> <StatusBadge status={outcome.paymentStatus} /></li>}
          {booking.paymentReference && <li><strong>Transaction reference:</strong> <code>{booking.paymentReference}</code></li>}
          <li><strong>Hotel:</strong> {booking.hotelName}, room {booking.roomNumber}</li>
          <li><strong>Stay:</strong> {formatDate(booking.checkIn)} → {formatDate(booking.checkOut)}</li>
          <li><strong>Amount:</strong> {formatMoney(booking.totalAmount)}</li>
          {!confirmed && !processing && <li><strong>Reason:</strong> {booking.statusReason}</li>}
        </ul>

        <div className="actions center">
          <Link className="btn btn-primary" to="/my-bookings">My bookings</Link>
          {unpaid && <Link className="btn btn-gold" to={`/bookings/${booking.bookingId}/pay`}>Pay again</Link>}
          {!confirmed && !processing && !unpaid && <Link className="btn btn-outline" to={`/rooms/${booking.roomId}`}>Try again</Link>}
        </div>
      </div>
    </div>
  );
}
