import { Link } from 'react-router-dom';
import { formatMoney } from './common';

const ROOM_TYPE_LABEL = { STANDARD: 'Standard Room', DELUXE: 'Deluxe Room', SUITE: 'Suite' };

export default function RoomCard({ room, search }) {
  const params = new URLSearchParams();
  if (search?.checkIn) params.set('checkIn', search.checkIn);
  if (search?.checkOut) params.set('checkOut', search.checkOut);
  if (search?.guests) params.set('guests', search.guests);

  return (
    <article className={`room-card room-${room.roomType.toLowerCase()}`}>
      <div className="room-card-banner">
        <span className="room-type-tag">{ROOM_TYPE_LABEL[room.roomType] || room.roomType}</span>
      </div>
      <div className="room-card-body">
        <h3>{room.hotelName}</h3>
        <p className="muted">{room.city} · Room {room.roomNumber} · up to {room.capacity} guests</p>
        {room.description && <p className="room-desc">{room.description}</p>}
        <div className="room-card-footer">
          <div>
            <span className="price">{formatMoney(room.basePrice)}</span>
            <span className="muted"> / night (base)</span>
          </div>
          <Link className="btn btn-primary btn-sm" to={`/rooms/${room.id}?${params.toString()}`}>View &amp; book</Link>
        </div>
      </div>
    </article>
  );
}
