import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { bookingApi, roomApi } from '../../api/endpoints';
import { Alert, Loading, formatMoney } from '../../components/common';
import { dashboardCounts } from '../../utils/bookingStatus';

export default function AdminDashboardPage() {
  const [summary, setSummary] = useState(null);
  const [bookings, setBookings] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    Promise.all([roomApi.inventorySummary(), bookingApi.all()])
      .then(([inventory, allBookings]) => { setSummary(inventory); setBookings(allBookings); })
      .catch((err) => setError(err.message));
  }, []);

  if (!summary) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;

  const counts = dashboardCounts(bookings);
  const revenue = counts.confirmedValue;
  const totalRooms = summary.reduce((sum, hotel) => sum + hotel.totalRooms, 0);

  return (
    <div className="container section">
      <h1>Admin dashboard</h1>
      <div className="grid grid-4 stats">
        <div className="card stat"><span>Hotels</span><strong>{summary.length}</strong></div>
        <div className="card stat"><span>Rooms</span><strong>{totalRooms}</strong></div>
        <div className="card stat"><span>Confirmed / Pending / Cancelled</span><strong>{counts.confirmed} / {counts.pending} / {counts.cancelled}</strong></div>
        <div className="card stat"><span>Confirmed booking value</span><strong>{formatMoney(revenue)}</strong></div>
      </div>

      <h2>Inventory by hotel (tonight)</h2>
      <div className="table-wrap card">
        <table className="table">
          <thead>
            <tr><th>Hotel</th><th>City</th><th>Total rooms</th><th>Active</th><th>Maintenance</th><th>Reserved tonight</th><th>Occupancy</th></tr>
          </thead>
          <tbody>
            {summary.map((hotel) => (
              <tr key={hotel.hotelId}>
                <td>{hotel.hotelName}</td>
                <td>{hotel.city}</td>
                <td>{hotel.totalRooms}</td>
                <td>{hotel.activeRooms}</td>
                <td>{hotel.maintenanceRooms}</td>
                <td>{hotel.reservedTonight}</td>
                <td>
                  <div className="meter"><div style={{ width: `${hotel.occupancyTonightPercent}%` }} /></div>
                  {hotel.occupancyTonightPercent}%
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="quick-links">
        <Link className="btn btn-primary" to="/admin/rooms/new">Add room</Link>
        <Link className="btn btn-outline" to="/admin/hotels">Manage hotels</Link>
        <Link className="btn btn-outline" to="/admin/pricing">Manage prices</Link>
        <Link className="btn btn-outline" to="/admin/bookings">View bookings</Link>
      </div>
    </div>
  );
}
