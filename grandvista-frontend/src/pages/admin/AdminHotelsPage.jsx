import { useCallback, useEffect, useState } from 'react';
import { hotelApi } from '../../api/endpoints';
import { Alert, FieldError, Loading } from '../../components/common';

const EMPTY = { name: '', city: '', state: '', country: 'India', address: '', description: '', starRating: 4 };

export default function AdminHotelsPage() {
  const [hotels, setHotels] = useState(null);
  const [form, setForm] = useState(EMPTY);
  const [editingId, setEditingId] = useState(null);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [message, setMessage] = useState(null);

  const load = useCallback(() => hotelApi.list().then(setHotels).catch((err) => setError(err.message)), []);
  useEffect(() => { load(); }, [load]);

  const submit = async (event) => {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    try {
      const payload = { ...form, starRating: Number(form.starRating) };
      const saved = editingId ? await hotelApi.update(editingId, payload) : await hotelApi.create(payload);
      setMessage(`Hotel "${saved.name}" ${editingId ? 'updated' : 'created'}.`);
      setForm(EMPTY);
      setEditingId(null);
      load();
    } catch (err) {
      setError(err.message);
      setFieldErrors(err.validationErrors);
    }
  };

  const edit = (hotel) => {
    setEditingId(hotel.id);
    setForm({ ...hotel, description: hotel.description || '' });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const update = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  return (
    <div className="container section">
      <h1>Hotels</h1>
      <Alert type="success" onClose={() => setMessage(null)}>{message}</Alert>
      <Alert>{error}</Alert>

      <form className="card form-grid" onSubmit={submit} noValidate>
        <h2 className="span-2">{editingId ? `Edit hotel #${editingId}` : 'Add a hotel'}</h2>
        <label>Name<input value={form.name} onChange={update('name')} /><FieldError errors={fieldErrors} name="name" /></label>
        <label>City<input value={form.city} onChange={update('city')} /><FieldError errors={fieldErrors} name="city" /></label>
        <label>State<input value={form.state} onChange={update('state')} /><FieldError errors={fieldErrors} name="state" /></label>
        <label>Country<input value={form.country} onChange={update('country')} /><FieldError errors={fieldErrors} name="country" /></label>
        <label className="span-2">Address<input value={form.address} onChange={update('address')} /><FieldError errors={fieldErrors} name="address" /></label>
        <label className="span-2">Description<textarea rows="2" value={form.description} onChange={update('description')} /></label>
        <label>Star rating
          <select value={form.starRating} onChange={update('starRating')}>
            {[1, 2, 3, 4, 5].map((stars) => <option key={stars} value={stars}>{stars}</option>)}
          </select>
        </label>
        <div className="actions span-2">
          <button className="btn btn-primary" type="submit">{editingId ? 'Save changes' : 'Create hotel'}</button>
          {editingId && <button className="btn btn-outline" type="button" onClick={() => { setEditingId(null); setForm(EMPTY); }}>Cancel</button>}
        </div>
      </form>

      {!hotels ? <Loading /> : (
        <div className="table-wrap card">
          <table className="table">
            <thead><tr><th>#</th><th>Name</th><th>City</th><th>State</th><th>Stars</th><th /></tr></thead>
            <tbody>
              {hotels.map((hotel) => (
                <tr key={hotel.id}>
                  <td>{hotel.id}</td><td>{hotel.name}</td><td>{hotel.city}</td><td>{hotel.state}</td>
                  <td>{'★'.repeat(hotel.starRating)}</td>
                  <td><button className="btn btn-outline btn-sm" type="button" onClick={() => edit(hotel)}>Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
