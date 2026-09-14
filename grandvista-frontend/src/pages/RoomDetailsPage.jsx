import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { bookingApi, roomApi } from '../api/endpoints';
import PriceBreakdown from '../components/PriceBreakdown';
import { Alert, Loading, formatMoney, isoDate } from '../components/common';
import { useAuth } from '../context/AuthContext';

export default function RoomDetailsPage() {
  const { roomId } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const { isAuthenticated, isGuest, isAdmin } = useAuth();

  const [room, setRoom] = useState(null);
  const [stay, setStay] = useState({
    checkIn: searchParams.get('checkIn') || isoDate(1),
    checkOut: searchParams.get('checkOut') || isoDate(3),
    numberOfGuests: searchParams.get('guests') || 1,
  });
  const [availability, setAvailability] = useState(null);
  const [quote, setQuote] = useState(null);
  const [error, setError] = useState(null);
  const [checking, setChecking] = useState(false);
  const [booking, setBooking] = useState(false);

  useEffect(() => {
    roomApi.get(roomId).then(setRoom).catch((err) => setError(err.message));
  }, [roomId]);

  const check = async () => {
    setError(null);
    setChecking(true);
    setAvailability(null);
    setQuote(null);
    try {
      // Two independent Room Service calls through the gateway, run in parallel.
      const [availabilityResult, quoteResult] = await Promise.all([
        roomApi.availability(roomId, stay.checkIn, stay.checkOut),
        roomApi.price(roomId, stay.checkIn, stay.checkOut),
      ]);
      setAvailability(availabilityResult);
      setQuote(quoteResult);
    } catch (err) {
      setError(err.message);
    } finally {
      setChecking(false);
    }
  };

  const reserve = async () => {
    setError(null);
    setBooking(true);
    try {
      const created = await bookingApi.create({
        roomId: Number(roomId),
        checkIn: stay.checkIn,
        checkOut: stay.checkOut,
        numberOfGuests: Number(stay.numberOfGuests),
      });
      navigate(`/bookings/${created.bookingId}/pay`);
    } catch (err) {
      // e.g. 409 "Room 203 is already reserved for some of the selected dates"
      setError(err.message);
      setAvailability(null);
    } finally {
      setBooking(false);
    }
  };

  const updateStay = (field) => (e) => {
    setStay({ ...stay, [field]: e.target.value });
    setAvailability(null);
    setQuote(null);
  };

  if (!room) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;

  const guestsTooMany = Number(stay.numberOfGuests) > room.capacity;

  return (
    <div className="container section">
      <Link to="/rooms" className="small">← Back to search</Link>
      <div className="details-layout">
        <section className="card">
          <div className={`details-banner room-${room.roomType.toLowerCase()}`}><span>{room.roomType}</span></div>
          <h1>{room.hotelName}</h1>
          <p className="muted">{room.city} · Room {room.roomNumber}</p>
          <p>{room.description}</p>
          <ul className="facts">
            <li><strong>Capacity:</strong> {room.capacity} guests</li>
            <li><strong>Base price:</strong> {formatMoney(room.basePrice)} / night</li>
            <li><strong>Status:</strong> {room.status}</li>
          </ul>
        </section>

        <aside className="card booking-panel">
          <h2>Your stay</h2>
          <label>Check-in<input type="date" min={isoDate(0)} value={stay.checkIn} onChange={updateStay('checkIn')} /></label>
          <label>Check-out<input type="date" min={stay.checkIn} value={stay.checkOut} onChange={updateStay('checkOut')} /></label>
          <label>Guests<input type="number" min="1" max={room.capacity} value={stay.numberOfGuests} onChange={updateStay('numberOfGuests')} /></label>
          {guestsTooMany && <small className="field-error">This room accommodates at most {room.capacity} guests.</small>}

          <button className="btn btn-outline btn-block" type="button" onClick={check} disabled={checking}>
            {checking ? 'Checking…' : 'Check availability & price'}
          </button>

          <Alert>{error}</Alert>
          {availability && (
            <Alert type={availability.available ? 'success' : 'warning'}>{availability.reason}</Alert>
          )}
          <PriceBreakdown quote={quote} />

          {availability?.available && (
            <>
              {isGuest && (
                <button className="btn btn-gold btn-block" type="button" onClick={reserve} disabled={booking || guestsTooMany}>
                  {booking ? 'Reserving…' : `Reserve for ${formatMoney(quote?.finalTotal)}`}
                </button>
              )}
              {!isAuthenticated && (
                <Link className="btn btn-gold btn-block" to="/login" state={{ from: location.pathname + location.search }}>
                  Sign in to reserve
                </Link>
              )}
              {isAdmin && <p className="small muted">Administrators cannot make guest bookings.</p>}
            </>
          )}
        </aside>
      </div>
    </div>
  );
}
