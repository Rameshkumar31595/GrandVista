import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { hotelApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { isoDate } from '../components/common';

export default function HomePage() {
  const navigate = useNavigate();
  const { isAdmin } = useAuth();
  const [hotels, setHotels] = useState([]);
  const [form, setForm] = useState({ city: '', checkIn: isoDate(1), checkOut: isoDate(3), guests: 2 });

  useEffect(() => {
    hotelApi.list().then(setHotels).catch(() => setHotels([]));
  }, []);

  const cities = [...new Set(hotels.map((hotel) => hotel.city))];

  const search = (event) => {
    event.preventDefault();
    const params = new URLSearchParams(Object.entries(form).filter(([, value]) => value !== ''));
    navigate(`/rooms?${params.toString()}`);
  };

  return (
    <>
      <section className="hero">
        <div className="container">
          <h1>Stay grand across India</h1>
          <p>Real-time availability, transparent dynamic pricing and instant confirmation at every GrandVista property.</p>

          <form className="search-bar" onSubmit={search}>
            <label>
              City
              <select value={form.city} onChange={(e) => setForm({ ...form, city: e.target.value })}>
                <option value="">All cities</option>
                {cities.map((city) => <option key={city}>{city}</option>)}
              </select>
            </label>
            <label>
              Check-in
              <input type="date" min={isoDate(0)} value={form.checkIn}
                onChange={(e) => setForm({ ...form, checkIn: e.target.value })} required />
            </label>
            <label>
              Check-out
              <input type="date" min={form.checkIn} value={form.checkOut}
                onChange={(e) => setForm({ ...form, checkOut: e.target.value })} required />
            </label>
            <label>
              Guests
              <input type="number" min="1" max="10" value={form.guests}
                onChange={(e) => setForm({ ...form, guests: e.target.value })} />
            </label>
            <button className="btn btn-gold" type="submit">Search rooms</button>
          </form>
          {isAdmin && <p className="hero-note">You are signed in as an administrator — use the Dashboard to manage inventory.</p>}
        </div>
      </section>

      <section className="container section">
        <h2>Our properties</h2>
        <div className="grid grid-4">
          {hotels.map((hotel) => (
            <article key={hotel.id} className="card hotel-card">
              <div className="stars">{'★'.repeat(hotel.starRating)}</div>
              <h3>{hotel.name}</h3>
              <p className="muted">{hotel.city}, {hotel.state}</p>
              <p className="small">{hotel.address}</p>
              <button type="button" className="btn btn-outline btn-sm"
                onClick={() => navigate(`/rooms?hotelId=${hotel.id}`)}>View rooms</button>
            </article>
          ))}
          {hotels.length === 0 && <p className="muted">Hotels will appear here once the Room Service is running.</p>}
        </div>
      </section>

      <section className="container section features">
        <div className="card"><h3>No double bookings</h3><p>Date-aware inventory with row-level locking guarantees one guest per room per night.</p></div>
        <div className="card"><h3>Fair dynamic pricing</h3><p>Seasonal and demand-based rules are applied transparently, night by night.</p></div>
        <div className="card"><h3>Secure by design</h3><p>JWT-secured APIs behind a single API Gateway with role-based access.</p></div>
      </section>
    </>
  );
}
