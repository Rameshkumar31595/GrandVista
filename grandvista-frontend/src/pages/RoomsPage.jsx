import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { hotelApi, roomApi } from '../api/endpoints';
import RoomCard from '../components/RoomCard';
import { Alert, EmptyState, Loading, isoDate } from '../components/common';

// 502/503/504 or no connection = a free-tier service is probably still waking up. Only this read-only search is
// retried, a bounded number of times; anything else (400, 401, 404, 500...) is shown immediately.
const WAKING_STATUSES = new Set([0, 502, 503, 504]);
export const WAKE_RETRY_DELAY_MS = 10000;
export const WAKE_MAX_RETRIES = 18;
const WAKING_MESSAGE = 'Rooms are taking a moment to load - the server may still be waking up. Retrying automatically...';
const WAKING_GIVE_UP_MESSAGE = 'Rooms could not be loaded right now. The server may be starting up or temporarily unavailable. Please try again in a minute.';

const FILTER_KEYS = ['city', 'hotelId', 'roomType', 'guests', 'maxPrice', 'checkIn', 'checkOut'];

export default function RoomsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const criteria = Object.fromEntries(FILTER_KEYS.map((key) => [key, searchParams.get(key) || '']));
  const [form, setForm] = useState(criteria);
  const [hotels, setHotels] = useState([]);
  const [rooms, setRooms] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [waking, setWaking] = useState(false);
  const hotelsLoaded = useRef(false);
  const [reloadToken, setReloadToken] = useState(0);
  const criteriaKey = searchParams.toString();

  useEffect(() => {
    let cancelled = false;
    hotelApi.list().then((list) => { hotelsLoaded.current = true; if (!cancelled) setHotels(list); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    let timer = null;
    setLoading(true);
    setError(null);
    setWaking(false);
    const attempt = (retriesDone) => {
      roomApi.search(Object.fromEntries(searchParams))
        .then((result) => {
          if (cancelled) return;
          setRooms(result);
          setWaking(false);
          setLoading(false);
          // the hotel list may have failed while the server was waking up: fetch it once more, only if still missing
          if (!hotelsLoaded.current) {
            hotelApi.list().then((list) => { hotelsLoaded.current = true; if (!cancelled) setHotels(list); }).catch(() => {});
          }
        })
        .catch((err) => {
          if (cancelled) return;
          if (WAKING_STATUSES.has(err.status) && retriesDone < WAKE_MAX_RETRIES) {
            setWaking(true);
            timer = setTimeout(() => attempt(retriesDone + 1), WAKE_RETRY_DELAY_MS);
            return;
          }
          setRooms([]);
          setWaking(false);
          setError(WAKING_STATUSES.has(err.status) ? WAKING_GIVE_UP_MESSAGE : err.message);
          setLoading(false);
        });
    };
    attempt(0);
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [criteriaKey, reloadToken]); // eslint-disable-line react-hooks/exhaustive-deps

  const apply = (event) => {
    event.preventDefault();
    setSearchParams(Object.fromEntries(Object.entries(form).filter(([, value]) => value !== '')));
  };

  const reset = () => {
    setForm(Object.fromEntries(FILTER_KEYS.map((key) => [key, ''])));
    setSearchParams({});
  };

  const update = (field) => (e) => setForm({ ...form, [field]: e.target.value });
  const cities = [...new Set(hotels.map((hotel) => hotel.city))];
  const datesChosen = criteria.checkIn && criteria.checkOut;

  return (
    <div className="container section">
      <h1>Find a room</h1>
      <form className="card filter-bar" onSubmit={apply}>
        <label>City
          <select value={form.city} onChange={update('city')}>
            <option value="">Any</option>
            {cities.map((city) => <option key={city}>{city}</option>)}
          </select>
        </label>
        <label>Hotel
          <select value={form.hotelId} onChange={update('hotelId')}>
            <option value="">Any</option>
            {hotels.map((hotel) => <option key={hotel.id} value={hotel.id}>{hotel.name}</option>)}
          </select>
        </label>
        <label>Room type
          <select value={form.roomType} onChange={update('roomType')}>
            <option value="">Any</option>
            <option>STANDARD</option><option>DELUXE</option><option>SUITE</option>
          </select>
        </label>
        <label>Guests<input type="number" min="1" max="10" value={form.guests} onChange={update('guests')} /></label>
        <label>Max base price<input type="number" min="1" step="any" value={form.maxPrice} onChange={update('maxPrice')} /></label>
        <label>Check-in<input type="date" min={isoDate(0)} value={form.checkIn} onChange={update('checkIn')} /></label>
        <label>Check-out<input type="date" min={form.checkIn || isoDate(1)} value={form.checkOut} onChange={update('checkOut')} /></label>
        <div className="filter-actions">
          <button className="btn btn-primary" type="submit">Search</button>
          <button className="btn btn-outline" type="button" onClick={reset}>Reset</button>
        </div>
      </form>

      {datesChosen
        ? <p className="muted">Showing rooms free for the whole stay {criteria.checkIn} → {criteria.checkOut}.</p>
        : <p className="muted">Tip: choose check-in and check-out dates to see only rooms available for your stay.</p>}

      <Alert>{error}</Alert>
      {error && <button type="button" className="btn btn-outline" onClick={() => setReloadToken((n) => n + 1)}>Try again</button>}
      {waking && <Alert type="info">{WAKING_MESSAGE}</Alert>}
      {loading ? <Loading text="Searching rooms…" /> : (
        rooms.length === 0 && !error
          ? <EmptyState title="No rooms match your search"><p>Try different dates or remove some filters.</p></EmptyState>
          : <div className="grid grid-3">{rooms.map((room) => <RoomCard key={room.id} room={room} search={criteria} />)}</div>
      )}
    </div>
  );
}
