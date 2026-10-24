import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { hotelApi, roomApi } from '../api/endpoints';
import RoomCard from '../components/RoomCard';
import { Alert, EmptyState, Loading, isoDate } from '../components/common';

const FILTER_KEYS = ['city', 'hotelId', 'roomType', 'guests', 'maxPrice', 'checkIn', 'checkOut'];

export default function RoomsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const criteria = Object.fromEntries(FILTER_KEYS.map((key) => [key, searchParams.get(key) || '']));
  const [form, setForm] = useState(criteria);
  const [hotels, setHotels] = useState([]);
  const [rooms, setRooms] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const criteriaKey = searchParams.toString();

  useEffect(() => {
    hotelApi.list().then(setHotels).catch(() => {});
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    roomApi.search(Object.fromEntries(searchParams))
      .then((result) => { if (!cancelled) setRooms(result); })
      .catch((err) => { if (!cancelled) { setRooms([]); setError(err.message); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [criteriaKey]); // eslint-disable-line react-hooks/exhaustive-deps

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
      {loading ? <Loading text="Searching rooms…" /> : (
        rooms.length === 0 && !error
          ? <EmptyState title="No rooms match your search"><p>Try different dates or remove some filters.</p></EmptyState>
          : <div className="grid grid-3">{rooms.map((room) => <RoomCard key={room.id} room={room} search={criteria} />)}</div>
      )}
    </div>
  );
}
