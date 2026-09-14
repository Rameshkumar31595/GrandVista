import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { roomApi } from '../../api/endpoints';
import { Alert, Loading, StatusBadge, formatDate, formatMoney } from '../../components/common';

export default function AdminRoomsPage() {
  const [rooms, setRooms] = useState(null);
  const [hotelFilter, setHotelFilter] = useState('');
  const [holds, setHolds] = useState({ roomId: null, items: [] });
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);

  const load = useCallback(() => roomApi.listAll().then(setRooms).catch((err) => setError(err.message)), []);
  useEffect(() => { load(); }, [load]);

  const run = async (action, successText) => {
    setError(null);
    try {
      await action();
      setMessage(successText);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  const showHolds = async (room) => {
    setError(null);
    try {
      setHolds({ roomId: room.id, label: `${room.hotelName} · Room ${room.roomNumber}`, items: await roomApi.holds(room.id) });
    } catch (err) {
      setError(err.message);
    }
  };

  if (!rooms) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;

  const hotelNames = [...new Set(rooms.map((room) => room.hotelName))];
  const visible = hotelFilter ? rooms.filter((room) => room.hotelName === hotelFilter) : rooms;

  return (
    <div className="container section">
      <div className="page-head">
        <h1>Room inventory</h1>
        <Link className="btn btn-primary" to="/admin/rooms/new">+ Add room</Link>
      </div>
      <Alert type="success" onClose={() => setMessage(null)}>{message}</Alert>
      <Alert>{error}</Alert>

      <label className="inline-filter">Hotel
        <select value={hotelFilter} onChange={(e) => setHotelFilter(e.target.value)}>
          <option value="">All hotels</option>
          {hotelNames.map((name) => <option key={name}>{name}</option>)}
        </select>
      </label>

      <div className="table-wrap card">
        <table className="table">
          <thead><tr><th>#</th><th>Hotel</th><th>Room</th><th>Type</th><th>Capacity</th><th>Base price</th><th>Status</th><th>Actions</th></tr></thead>
          <tbody>
            {visible.map((room) => (
              <tr key={room.id}>
                <td>{room.id}</td>
                <td>{room.hotelName}<br /><small className="muted">{room.city}</small></td>
                <td>{room.roomNumber}</td>
                <td>{room.roomType}</td>
                <td>{room.capacity}</td>
                <td>{formatMoney(room.basePrice)}</td>
                <td>
                  <select value={room.status} aria-label="Room status"
                    onChange={(e) => run(() => roomApi.updateStatus(room.id, e.target.value), `Room ${room.roomNumber} set to ${e.target.value}.`)}>
                    <option>ACTIVE</option><option>MAINTENANCE</option><option>INACTIVE</option>
                  </select>
                </td>
                <td className="row-actions">
                  <Link className="btn btn-outline btn-sm" to={`/admin/rooms/${room.id}/edit`}>Edit</Link>
                  <button className="btn btn-outline btn-sm" type="button" onClick={() => showHolds(room)}>Reservations</button>
                  {room.status !== 'INACTIVE' && (
                    <button className="btn btn-danger btn-sm" type="button"
                      onClick={() => window.confirm(`Deactivate room ${room.roomNumber}?`)
                        && run(() => roomApi.deactivate(room.id), `Room ${room.roomNumber} deactivated.`)}>
                      Deactivate
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {holds.roomId && (
        <div className="card section-sm">
          <div className="page-head">
            <h2>Upcoming reservations – {holds.label}</h2>
            <button className="btn btn-outline btn-sm" type="button" onClick={() => setHolds({ roomId: null, items: [] })}>Close</button>
          </div>
          {holds.items.length === 0 ? <p className="muted">No current or upcoming reservations.</p> : (
            <table className="table compact">
              <thead><tr><th>Hold #</th><th>Check-in</th><th>Check-out</th><th>Guests</th><th>Amount</th><th>Status</th></tr></thead>
              <tbody>
                {holds.items.map((hold) => (
                  <tr key={hold.holdId}>
                    <td>{hold.holdId}</td><td>{formatDate(hold.checkIn)}</td><td>{formatDate(hold.checkOut)}</td>
                    <td>{hold.guests}</td><td>{formatMoney(hold.totalAmount)}</td><td><StatusBadge status={hold.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
