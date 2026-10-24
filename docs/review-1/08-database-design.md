# 08 – Database Design

## 1. Strategy: database per service

One PostgreSQL 18 server (development: `localhost:5433`) hosts four independent databases. Each service's
`application.properties` contains exactly one JDBC URL:

| Service | JDBC URL | Tables |
|---|---|---|
| AUTH-SERVICE | `jdbc:postgresql://${DB_HOST}:${DB_PORT}/grandvista_auth_db` | `users` |
| ROOM-SERVICE | `…/grandvista_room_db` | `hotels`, `rooms`, `room_holds`, `pricing_rules` |
| BOOKING-SERVICE | `…/grandvista_booking_db` | `bookings` |
| PAYMENT-SERVICE | `…/grandvista_payment_db` | `payments` |

**Benefits:** services can change their schema independently; a slow query in bookings cannot lock rooms; each
database could later move to its own server. **Cost:** no cross-database joins or foreign keys – consistency across
services is handled by the booking workflow (see `09-booking-workflow.md`).

Tables are created by Hibernate (`ddl-auto=update`, **development convenience**). The exact generated DDL is in
[`docs/database/02-schema-reference.sql`](../database/02-schema-reference.sql); ownership and ER diagrams are in
[`docs/architecture/architecture-diagrams.md`](../architecture/architecture-diagrams.md) (E, F).

All timestamps are `timestamp with time zone` stored in UTC; money is `numeric` (never floating point); enums are
stored as readable strings with `CHECK` constraints.

## 2. grandvista_auth_db

### `users`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | bigint identity | **PK** | also the JWT `sub` |
| `name` | varchar(100) | not null | |
| `email` | varchar(150) | not null, **unique** `uk_users_email` | stored lower-case |
| `password_hash` | varchar(100) | not null | BCrypt (`$2a$10$…`), never plain text |
| `role` | varchar(20) | not null, check `ROLE_USER`/`ROLE_ADMIN` | |
| `created_at`, `updated_at` | timestamptz | not null | set by `@PrePersist/@PreUpdate` |

## 3. grandvista_room_db

### `hotels`

| Column | Type | Constraints |
|---|---|---|
| `id` | bigint identity | **PK** |
| `name` | varchar(120) | not null; **unique (name, city)** `uk_hotels_name_city` |
| `city`, `state`, `country` | varchar(80) | not null |
| `address` | varchar(255) | not null |
| `description` | varchar(1000) | |
| `star_rating` | integer | not null (1–5 validated in API) |
| `active` | boolean | not null |
| `created_at`, `updated_at` | timestamptz | not null |

### `rooms`

| Column | Type | Constraints |
|---|---|---|
| `id` | bigint identity | **PK** |
| `hotel_id` | bigint | not null, **FK → hotels(id)** `fk_rooms_hotel` |
| `room_number` | varchar(20) | not null; **unique (hotel_id, room_number)** |
| `room_type` | varchar(20) | check `STANDARD`/`DELUXE`/`SUITE` |
| `base_price` | numeric(10,2) | not null (> 0 validated) |
| `capacity` | integer | not null (1–10 validated) |
| `description` | varchar(1000) | |
| `status` | varchar(20) | check `ACTIVE`/`MAINTENANCE`/`INACTIVE` |
| `created_at`, `updated_at` | timestamptz | not null |

`status` is the physical/operational state only. **Date availability is not a column** – it is derived from
`room_holds`.

### `room_holds` – the date-aware inventory

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | bigint identity | **PK** | referenced by `bookings.hold_id` (other DB, no FK) |
| `room_id` | bigint | not null, **FK → rooms(id)** | |
| `user_id` | bigint | not null | auth user id, **no FK** |
| `check_in` | date | not null | inclusive |
| `check_out` | date | not null | **exclusive** |
| `guests` | integer | not null | |
| `total_amount` | numeric(12,2) | not null | price calculated when the hold was created |
| `status` | varchar(20) | check `HELD`/`CONFIRMED`/`RELEASED`/`EXPIRED` | |
| `expires_at` | timestamptz | nullable | HELD rows block only while `expires_at > now()`; null once confirmed |
| `created_at`, `updated_at` | timestamptz | not null | |

Index `idx_room_holds_room_dates (room_id, check_in, check_out)` supports the overlap query:

```sql
SELECT count(*) FROM room_holds
WHERE room_id = :roomId
  AND check_in  < :requestedCheckOut
  AND check_out > :requestedCheckIn
  AND (status = 'CONFIRMED' OR (status = 'HELD' AND expires_at > now()));
```

### `pricing_rules`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | bigint identity | **PK** | |
| `name` | varchar(100) | not null | |
| `rule_type` | varchar(20) | check `SEASONAL`/`OCCUPANCY` | |
| `multiplier` | numeric(4,2) | not null | 0.50–3.00 validated |
| `start_date`, `end_date` | date | SEASONAL only | inclusive range of nights |
| `min_occupancy_percent` | integer | OCCUPANCY only | 1–100 |
| `hotel_id` | bigint | nullable | optional scope, null = all hotels |
| `room_type` | varchar(20) | nullable | optional scope |
| `active` | boolean | not null | |
| `created_at`, `updated_at` | timestamptz | not null | |

Relationships inside the room database: `hotels 1 ─ * rooms 1 ─ * room_holds` (real FKs, same database).

## 4. grandvista_booking_db

### `bookings`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | bigint identity | **PK** | |
| `user_id` | bigint | not null, index `idx_bookings_user` | JWT subject, no FK |
| `room_id`, `hotel_id`, `hold_id` | bigint | not null | Room Service ids, no FK |
| `hotel_name`, `room_number`, `room_type` | varchar | not null | **snapshot** at booking time for display |
| `check_in`, `check_out` | date | not null | |
| `number_of_guests` | integer | not null | |
| `total_amount` | numeric(12,2) | not null | from Room Service, never from client |
| `status` | varchar(20) | check `PENDING`/`PAYMENT_IN_PROGRESS`/`CONFIRMED`/`CANCELLATION_PENDING`/`CANCELLED`; index `idx_bookings_status` | the two intermediate states are persisted recovery states (see `09-booking-workflow.md`); on an existing database `BookingStatusConstraintUpdater` widens the old 3-value constraint at start-up |
| `status_reason` | varchar(255) | | e.g. "Payment failed: …" |
| `hold_expires_at` | timestamptz | | payment deadline |
| `payment_id` | bigint | | Payment Service id, no FK |
| `payment_reference` | varchar(40) | | transaction reference for display |
| `version` | bigint | not null | JPA optimistic lock |
| `created_at`, `updated_at` | timestamptz | not null | |

## 5. grandvista_payment_db

### `payments`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | bigint identity | **PK** | |
| `booking_id` | bigint | not null, index | booking id, no FK |
| `user_id` | bigint | not null, index | |
| `amount` | numeric(12,2) | not null | |
| `currency` | varchar(3) | not null | `INR` |
| `method` | varchar(20) | check `CARD`/`UPI`/`NET_BANKING`/`WALLET` | **type only – no card/UPI/bank data** |
| `status` | varchar(20) | check `SUCCESS`/`FAILED`/`REFUNDED` | |
| `transaction_reference` | varchar(40) | not null, **unique** | `GV-PAY-yyyymmdd-XXXXXXXX` |
| `failure_reason` | varchar(255) | | |
| `created_at`, `updated_at` | timestamptz | not null | |
| `refunded_at` | timestamptz | | |
| `success_booking_id` | bigint | **unique** (`uk_payments_success_booking`), nullable | set to `booking_id` when the payment succeeded and kept after a refund; guarantees at most one successful charge per booking even if two requests race |

Several FAILED attempts per booking are allowed (history); the service rejects a second SUCCESS for the same
booking.

## 6. Concurrency controls at the data layer

| Mechanism | Table | Protects against |
|---|---|---|
| `SELECT … FOR UPDATE` on `rooms` row before overlap check + insert | rooms / room_holds | two simultaneous holds for the same room and dates |
| `SELECT … FOR UPDATE` on `room_holds` row in confirm/release | room_holds | confirm and release racing |
| `SELECT … FOR UPDATE` on `bookings` row during payment/cancel | bookings | double payment, pay vs cancel race |
| `@Version` optimistic locking | bookings | lost updates (e.g. expiry job vs user action) |
| `SELECT … FOR UPDATE` on `payments` row during refund | payments | double refund |
| Unique constraints | users.email, rooms(hotel_id, room_number), hotels(name, city), payments.transaction_reference, **payments.success_booking_id** | duplicates under concurrent inserts, **double charge of one booking** |

## 7. Demonstration data

Room Service seeds (only into an empty database, `SEED_DEMO_DATA=true`):

| Hotel (fictional) | City | Rooms (101 STD, 102 STD, 203 DLX, 301 SUITE) base INR |
|---|---|---|
| GrandVista Banjara Residency ★5 | Hyderabad | 4200 / 4200 / 6500 / 11000 |
| GrandVista Krishna Riverside ★4 | Vijayawada | 2800 / 2800 / 4300 / 7500 |
| GrandVista Indiranagar Suites ★4 | Bengaluru | 3900 / 3900 / 5800 / 9800 |
| GrandVista Marina Bay ★5 | Chennai | 4000 / 4000 / 6200 / 12500 |

Pricing rules: *Dasara & Diwali Festive Season* (15 Oct–15 Nov, ×1.25), *Year-End Holidays* (20 Dec–5 Jan, ×1.40),
*High Demand* (occupancy ≥ 50 %, ×1.10), *Peak Demand* (occupancy ≥ 75 %, ×1.25).
Auth Service creates the admin account from configuration; guest accounts are created by registering.
