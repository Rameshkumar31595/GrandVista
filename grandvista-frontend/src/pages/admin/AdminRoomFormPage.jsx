import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { hotelApi, roomApi } from '../../api/endpoints';
import { Alert, FieldError, Loading } from '../../components/common';

const EMPTY = { hotelId: '', roomNumber: '', roomType: 'STANDARD', basePrice: '', capacity: 2, description: '' };

export default function AdminRoomFormPage() {
  const { roomId } = useParams();
  const editing = Boolean(roomId);
  const navigate = useNavigate();
  const [hotels, setHotels] = useState([]);
  const [form, setForm] = useState(editing ? null : EMPTY);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});

  useEffect(() => {
    hotelApi.list().then(setHotels).catch((err) => setError(err.message));
    if (editing) {
      roomApi.get(roomId)
        .then((room) => setForm({
          hotelId: room.hotelId, roomNumber: room.roomNumber, roomType: room.roomType,
          basePrice: room.basePrice, capacity: room.capacity, description: room.description || '',
        }))
        .catch((err) => setError(err.message));
    }
  }, [editing, roomId]);

  const submit = async (event) => {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    const payload = {
      ...form,
      hotelId: form.hotelId ? Number(form.hotelId) : null,
      basePrice: form.basePrice === '' ? null : Number(form.basePrice),
      capacity: form.capacity === '' ? null : Number(form.capacity),
    };
    try {
      if (editing) await roomApi.update(roomId, payload);
      else await roomApi.create(payload);
      navigate('/admin/rooms');
    } catch (err) {
      setError(err.message);
      setFieldErrors(err.validationErrors);
    }
  };

  if (!form) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;
  const update = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  return (
    <div className="container section narrow">
      <Link to="/admin/rooms" className="small">← Back to rooms</Link>
      <h1>{editing ? `Edit room #${roomId}` : 'Add room'}</h1>
      <Alert>{error}</Alert>
      <form className="card form-grid" onSubmit={submit} noValidate>
        <label className="span-2">Hotel
          <select value={form.hotelId} onChange={update('hotelId')} disabled={editing}>
            <option value="">Select a hotel</option>
            {hotels.map((hotel) => <option key={hotel.id} value={hotel.id}>{hotel.name} ({hotel.city})</option>)}
          </select>
          <FieldError errors={fieldErrors} name="hotelId" />
        </label>
        <label>Room number<input value={form.roomNumber} onChange={update('roomNumber')} /><FieldError errors={fieldErrors} name="roomNumber" /></label>
        <label>Room type
          <select value={form.roomType} onChange={update('roomType')}>
            <option>STANDARD</option><option>DELUXE</option><option>SUITE</option>
          </select>
        </label>
        <label>Base price per night (INR)<input type="number" min="1" step="0.01" value={form.basePrice} onChange={update('basePrice')} /><FieldError errors={fieldErrors} name="basePrice" /></label>
        <label>Capacity (guests)<input type="number" min="1" max="10" value={form.capacity} onChange={update('capacity')} /><FieldError errors={fieldErrors} name="capacity" /></label>
        <label className="span-2">Description<textarea rows="3" value={form.description} onChange={update('description')} /></label>
        <div className="actions span-2">
          <button className="btn btn-primary" type="submit">{editing ? 'Save changes' : 'Create room'}</button>
        </div>
      </form>
    </div>
  );
}
