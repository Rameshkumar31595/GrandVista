import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { bookingApi } from '../api/endpoints';
import { Alert, Loading, StatusBadge, formatDate, formatMoney } from '../components/common';
import { isInFlight, statusLabel } from '../utils/bookingStatus';
import { usePolling } from '../utils/usePolling';

const METHODS = [
  { value: 'UPI', label: 'UPI' },
  { value: 'CARD', label: 'Credit / Debit card' },
  { value: 'NET_BANKING', label: 'Net banking' },
  { value: 'WALLET', label: 'Wallet' },
];

function useCountdown(deadline) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  if (!deadline) return null;
  const remaining = Math.max(new Date(deadline).getTime() - now, 0);
  return { expired: remaining === 0, text: `${Math.floor(remaining / 60000)}:${String(Math.floor(remaining / 1000) % 60).padStart(2, '0')}` };
}

export default function PaymentPage() {
  const { bookingId } = useParams();
  const navigate = useNavigate();
  const [booking, setBooking] = useState(null);
  const [method, setMethod] = useState('UPI');
  const [simulateFailure, setSimulateFailure] = useState(false);
  const [error, setError] = useState(null);
  const [processing, setProcessing] = useState(false);
  const countdown = useCountdown(booking?.paymentDueBy);

  useEffect(() => {
    bookingApi.get(bookingId).then(setBooking).catch((err) => setError(err.message));
  }, [bookingId]);

  // A payment started earlier (or in another tab) may still be settling: follow it instead of offering "Pay" again.
  usePolling(() => bookingApi.get(bookingId).then(setBooking).catch(() => {}), isInFlight(booking?.bookingStatus));

  const pay = async () => {
    setError(null);
    setProcessing(true);
    try {
      const outcome = await bookingApi.pay(bookingId, { paymentMethod: method, simulateFailure });
      navigate(`/bookings/${bookingId}/result`, { state: { outcome } });
    } catch (err) {
      setError(err.message);
    } finally {
      setProcessing(false);
    }
  };

  const cancel = async () => {
    setError(null);
    try {
      setBooking(await bookingApi.cancel(bookingId));
    } catch (err) {
      setError(err.message);
    }
  };

  if (!booking) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;

  return (
    <div className="container section narrow">
      <h1>Complete your payment</h1>
      <div className="card">
        <div className="summary-head">
          <div>
            <h2>{booking.hotelName}</h2>
            <p className="muted">Room {booking.roomNumber} · {booking.roomType}</p>
          </div>
          <StatusBadge status={booking.bookingStatus} />
        </div>
        <ul className="facts">
          <li><strong>Booking #</strong>{booking.bookingId}</li>
          <li><strong>Stay:</strong> {formatDate(booking.checkIn)} → {formatDate(booking.checkOut)} ({booking.nights} nights)</li>
          <li><strong>Guests:</strong> {booking.numberOfGuests}</li>
          <li><strong>Amount due:</strong> <span className="price">{formatMoney(booking.totalAmount)}</span></li>
        </ul>

        {booking.bookingStatus !== 'PENDING' ? (
          <>
            <Alert type="info">This booking is {statusLabel(booking.bookingStatus)}: {booking.statusReason}</Alert>
            <Link className="btn btn-outline" to="/my-bookings">Go to my bookings</Link>
          </>
        ) : (
          <>
            {countdown && (
              <Alert type={countdown.expired ? 'warning' : 'info'}>
                {countdown.expired
                  ? 'The payment window has expired - the room hold has been released.'
                  : `The room is held for you. Complete payment within ${countdown.text} minutes.`}
              </Alert>
            )}

            <fieldset className="methods">
              <legend>Payment method (simulated - no real money)</legend>
              {METHODS.map((option) => (
                <label key={option.value} className={`method ${method === option.value ? 'selected' : ''}`}>
                  <input type="radio" name="method" value={option.value} checked={method === option.value}
                    onChange={() => setMethod(option.value)} />
                  {option.label}
                </label>
              ))}
            </fieldset>

            <label className="checkbox demo-toggle">
              <input type="checkbox" checked={simulateFailure} onChange={(e) => setSimulateFailure(e.target.checked)} />
              Demo: simulate a declined payment
            </label>
            <p className="small muted">GrandVista never asks for card numbers, CVV or UPI PIN in this academic demo.</p>

            <Alert>{error}</Alert>
            <div className="actions">
              <button className="btn btn-gold" type="button" onClick={pay} disabled={processing || countdown?.expired}>
                {processing ? 'Processing payment…' : `Pay ${formatMoney(booking.totalAmount)}`}
              </button>
              <button className="btn btn-outline" type="button" onClick={cancel} disabled={processing}>Cancel booking</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
