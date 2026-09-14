import { useCallback, useEffect, useState } from 'react';
import { hotelApi, pricingApi, roomApi } from '../../api/endpoints';
import { Alert, Loading, formatDate, formatMoney } from '../../components/common';

const EMPTY_RULE = {
  name: '', ruleType: 'SEASONAL', multiplier: '1.20', startDate: '', endDate: '', minOccupancyPercent: '',
  hotelId: '', roomType: '', active: true,
};

export default function AdminPricingPage() {
  const [rooms, setRooms] = useState(null);
  const [rules, setRules] = useState([]);
  const [hotels, setHotels] = useState([]);
  const [prices, setPrices] = useState({});
  const [ruleForm, setRuleForm] = useState(EMPTY_RULE);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);

  const load = useCallback(() => {
    Promise.all([roomApi.listAll(), pricingApi.list(), hotelApi.list()])
      .then(([allRooms, allRules, allHotels]) => { setRooms(allRooms); setRules(allRules); setHotels(allHotels); })
      .catch((err) => setError(err.message));
  }, []);
  useEffect(() => { load(); }, [load]);

  const act = async (action, text) => {
    setError(null);
    try {
      await action();
      setMessage(text);
      load();
    } catch (err) {
      const details = Object.values(err.validationErrors || {}).join('; ');
      setError(details ? `${err.message}: ${details}` : err.message);
    }
  };

  const savePrice = (room) => act(() => roomApi.updatePrice(room.id, Number(prices[room.id])),
    `Base price of room ${room.roomNumber} (${room.hotelName}) updated.`);

  const ruleToPayload = (rule) => ({
    name: rule.name,
    ruleType: rule.ruleType,
    multiplier: Number(rule.multiplier),
    startDate: rule.ruleType === 'SEASONAL' ? rule.startDate || null : null,
    endDate: rule.ruleType === 'SEASONAL' ? rule.endDate || null : null,
    minOccupancyPercent: rule.ruleType === 'OCCUPANCY' && rule.minOccupancyPercent !== '' ? Number(rule.minOccupancyPercent) : null,
    hotelId: rule.hotelId ? Number(rule.hotelId) : null,
    roomType: rule.roomType || null,
    active: rule.active,
  });

  const createRule = (event) => {
    event.preventDefault();
    act(async () => { await pricingApi.create(ruleToPayload(ruleForm)); setRuleForm(EMPTY_RULE); },
      `Pricing rule "${ruleForm.name}" created.`);
  };

  const toggleRule = (rule) => act(() => pricingApi.update(rule.id, ruleToPayload({
    ...rule, startDate: rule.startDate || '', endDate: rule.endDate || '', hotelId: rule.hotelId || '',
    roomType: rule.roomType || '', minOccupancyPercent: rule.minOccupancyPercent ?? '', active: !rule.active,
  })), `Rule "${rule.name}" ${rule.active ? 'disabled' : 'enabled'}.`);

  if (!rooms) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;
  const update = (field) => (e) => setRuleForm({ ...ruleForm, [field]: e.target.value });
  const hotelName = (id) => hotels.find((hotel) => hotel.id === id)?.name || `Hotel ${id}`;

  return (
    <div className="container section">
      <h1>Pricing</h1>
      <p className="muted">Final price per night = base price × seasonal multiplier × demand (occupancy) multiplier. The highest matching rule of each type applies.</p>
      <Alert type="success" onClose={() => setMessage(null)}>{message}</Alert>
      <Alert>{error}</Alert>

      <h2>Dynamic pricing rules</h2>
      <div className="table-wrap card">
        <table className="table">
          <thead><tr><th>Name</th><th>Type</th><th>Multiplier</th><th>Applies when</th><th>Scope</th><th>Active</th><th /></tr></thead>
          <tbody>
            {rules.map((rule) => (
              <tr key={rule.id} className={rule.active ? '' : 'inactive-row'}>
                <td>{rule.name}</td>
                <td>{rule.ruleType}</td>
                <td>× {Number(rule.multiplier).toFixed(2)}</td>
                <td>{rule.ruleType === 'SEASONAL'
                  ? `${formatDate(rule.startDate)} – ${formatDate(rule.endDate)}`
                  : `occupancy ≥ ${rule.minOccupancyPercent}%`}</td>
                <td>{rule.hotelId ? hotelName(rule.hotelId) : 'All hotels'} / {rule.roomType || 'All types'}</td>
                <td>{rule.active ? 'Yes' : 'No'}</td>
                <td className="row-actions">
                  <button className="btn btn-outline btn-sm" type="button" onClick={() => toggleRule(rule)}>{rule.active ? 'Disable' : 'Enable'}</button>
                  <button className="btn btn-danger btn-sm" type="button"
                    onClick={() => window.confirm(`Delete rule "${rule.name}"?`) && act(() => pricingApi.remove(rule.id), 'Rule deleted.')}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <form className="card form-grid section-sm" onSubmit={createRule}>
        <h3 className="span-2">Add pricing rule</h3>
        <label>Name<input value={ruleForm.name} onChange={update('name')} required /></label>
        <label>Type
          <select value={ruleForm.ruleType} onChange={update('ruleType')}>
            <option value="SEASONAL">SEASONAL (date range)</option>
            <option value="OCCUPANCY">OCCUPANCY (demand)</option>
          </select>
        </label>
        <label>Multiplier (0.50 – 3.00)<input type="number" step="0.01" min="0.5" max="3" value={ruleForm.multiplier} onChange={update('multiplier')} required /></label>
        {ruleForm.ruleType === 'SEASONAL' ? (
          <>
            <label>Start date<input type="date" value={ruleForm.startDate} onChange={update('startDate')} /></label>
            <label>End date<input type="date" value={ruleForm.endDate} onChange={update('endDate')} /></label>
          </>
        ) : (
          <label>Minimum occupancy %<input type="number" min="1" max="100" value={ruleForm.minOccupancyPercent} onChange={update('minOccupancyPercent')} /></label>
        )}
        <label>Hotel (optional)
          <select value={ruleForm.hotelId} onChange={update('hotelId')}>
            <option value="">All hotels</option>
            {hotels.map((hotel) => <option key={hotel.id} value={hotel.id}>{hotel.name}</option>)}
          </select>
        </label>
        <label>Room type (optional)
          <select value={ruleForm.roomType} onChange={update('roomType')}>
            <option value="">All types</option><option>STANDARD</option><option>DELUXE</option><option>SUITE</option>
          </select>
        </label>
        <div className="actions span-2"><button className="btn btn-primary" type="submit">Create rule</button></div>
      </form>

      <h2>Base prices</h2>
      <div className="table-wrap card">
        <table className="table">
          <thead><tr><th>Hotel</th><th>Room</th><th>Type</th><th>Current base price</th><th>New base price</th><th /></tr></thead>
          <tbody>
            {rooms.map((room) => (
              <tr key={room.id}>
                <td>{room.hotelName}</td><td>{room.roomNumber}</td><td>{room.roomType}</td>
                <td>{formatMoney(room.basePrice)}</td>
                <td><input className="input-sm" type="number" min="1" step="0.01" placeholder={room.basePrice}
                  value={prices[room.id] ?? ''} onChange={(e) => setPrices({ ...prices, [room.id]: e.target.value })} /></td>
                <td><button className="btn btn-primary btn-sm" type="button" disabled={!prices[room.id]} onClick={() => savePrice(room)}>Update</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
