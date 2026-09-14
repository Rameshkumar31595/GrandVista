-- Handy read-only queries for demonstrating the data in pgAdmin4.
-- Open the Query Tool on the database named in each section.

-- ===== grandvista_auth_db =====
-- Passwords are BCrypt hashes ($2a$10$...), never plain text.
SELECT id, name, email, role, left(password_hash, 7) || '…' AS password_hash_prefix, created_at
FROM users ORDER BY id;

-- ===== grandvista_room_db =====
-- Rooms per hotel
SELECT h.city, h.name AS hotel, r.room_number, r.room_type, r.base_price, r.capacity, r.status
FROM rooms r JOIN hotels h ON h.id = r.hotel_id
ORDER BY h.city, r.room_number;

-- Current reservations blocking inventory (the collision-prevention data)
SELECT h.name AS hotel, r.room_number, rh.check_in, rh.check_out, rh.status, rh.expires_at, rh.user_id
FROM room_holds rh JOIN rooms r ON r.id = rh.room_id JOIN hotels h ON h.id = r.hotel_id
WHERE rh.status = 'CONFIRMED' OR (rh.status = 'HELD' AND rh.expires_at > now())
ORDER BY h.name, r.room_number, rh.check_in;

-- Overlap check for one room, same rule the service uses (exclusive check-out)
-- replace :room_id / dates
SELECT count(*) AS conflicting_holds
FROM room_holds
WHERE room_id = 3
  AND check_in  < DATE '2026-09-25'   -- requested check-out
  AND check_out > DATE '2026-09-22'   -- requested check-in
  AND (status = 'CONFIRMED' OR (status = 'HELD' AND expires_at > now()));

SELECT id, name, rule_type, multiplier, start_date, end_date, min_occupancy_percent, hotel_id, room_type, active
FROM pricing_rules ORDER BY rule_type, id;

-- ===== grandvista_booking_db =====
SELECT id, user_id, hotel_name, room_number, check_in, check_out, total_amount, status, status_reason, payment_reference
FROM bookings ORDER BY id DESC;

-- ===== grandvista_payment_db =====
SELECT id, booking_id, user_id, amount, method, status, transaction_reference, failure_reason, created_at, refunded_at
FROM payments ORDER BY id DESC;
