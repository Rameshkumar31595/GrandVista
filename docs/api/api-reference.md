# API Reference

Base URL for clients: **`http://localhost:8080/api`** (API Gateway). The gateway strips `/api` and forwards to the
service shown. Interactive docs per service (direct port, development only):
`http://localhost:8081/swagger-ui.html` (auth), `:8082` (room), `:8083` (booking), `:8084` (payment).
Use *Authorize* with a token from `/auth/login`.

Legend: **P** public · **A** any authenticated · **U** `ROLE_USER` · **AD** `ROLE_ADMIN` · **O** owner or admin.

## Error format (all services)

```json
{
  "timestamp": "2026-09-14T05:30:12.345Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/bookings",
  "validationErrors": { "checkOut": "Check-out date is required" }
}
```

| Status | Typical cause |
|---|---|
| 400 | Bean Validation failure, malformed JSON, reversed/past dates |
| 401 | Missing, malformed or expired JWT; wrong login credentials |
| 403 | Role not allowed, not the owner, internal endpoint without service key |
| 404 | Unknown hotel/room/booking/payment |
| 409 | Duplicate email/room, room already reserved, booking not PENDING (including PAYMENT_IN_PROGRESS), payment replay with a different amount |
| 202 | `POST /bookings/{id}/pay` only: the payment outcome is not known yet (see below) |
| 503 | Downstream service unavailable |

## AUTH-SERVICE (`/api/auth/**` → `lb://AUTH-SERVICE`)

| Method | Path | Access | Body / params | Response |
|---|---|---|---|---|
| POST | `/auth/register` | P | `{name, email, password}` | 201 `UserResponse` |
| POST | `/auth/login` | P | `{email, password}` | 200 `{accessToken, tokenType, expiresAt, user}` |
| GET | `/auth/me` | A | – | `UserResponse` |
| GET | `/auth/users` | AD | – | `UserResponse[]` |
| PATCH | `/auth/users/{userId}/role` | AD | `{role: "ROLE_ADMIN"\|"ROLE_USER"}` | `UserResponse` |

`UserResponse`: `{id, name, email, role, createdAt}`

## ROOM-SERVICE (`/api/rooms/**`, `/api/hotels/**`, `/api/pricing-rules/**` → `lb://ROOM-SERVICE`)

### Hotels

| Method | Path | Access | Body |
|---|---|---|---|
| GET | `/hotels` | P | – |
| GET | `/hotels/{hotelId}` | P | – |
| POST | `/hotels` | AD | `{name, city, state, country, address, description?, starRating 1-5}` |
| PUT | `/hotels/{hotelId}` | AD | same |

### Rooms

| Method | Path | Access | Body / params |
|---|---|---|---|
| GET | `/rooms` | P | query `city, hotelId, roomType, guests, maxPrice, checkIn, checkOut` (all optional; dates both or none) |
| GET | `/rooms/{roomId}` | P | – |
| GET | `/rooms/{roomId}/availability` | P | query `checkIn, checkOut` (yyyy-MM-dd) → `{roomId, checkIn, checkOut, nights, available, reason}` |
| GET | `/rooms/{roomId}/price` | P | query `checkIn, checkOut` → `PriceQuoteResponse` |
| GET | `/rooms/all` | AD | all rooms incl. inactive |
| GET | `/rooms/inventory-summary` | AD | per hotel: `{hotelId, hotelName, city, totalRooms, activeRooms, maintenanceRooms, reservedTonight, occupancyTonightPercent}` |
| GET | `/rooms/{roomId}/holds` | AD | current + upcoming holds |
| POST | `/rooms` | AD | `{hotelId, roomNumber, roomType, basePrice, capacity 1-10, description?}` |
| PUT | `/rooms/{roomId}` | AD | same (hotel cannot change) |
| PATCH | `/rooms/{roomId}/price` | AD | `{basePrice}` |
| PATCH | `/rooms/{roomId}/status` | AD | `{status: ACTIVE\|MAINTENANCE\|INACTIVE}` |
| DELETE | `/rooms/{roomId}` | AD | soft delete → INACTIVE |

`PriceQuoteResponse`:

```json
{
  "roomId": 3, "checkIn": "2026-10-20", "checkOut": "2026-10-22", "nights": 2, "currency": "INR",
  "basePricePerNight": 6500.00, "baseTotal": 13000.00, "occupancyPercent": 25, "demandMultiplier": 1.00,
  "appliedRules": ["Dasara & Diwali Festive Season"],
  "nightlyBreakdown": [
    {"date": "2026-10-20", "seasonalMultiplier": 1.25, "demandMultiplier": 1.00, "price": 8125.00},
    {"date": "2026-10-21", "seasonalMultiplier": 1.25, "demandMultiplier": 1.00, "price": 8125.00}
  ],
  "pricingAdjustment": 3250.00, "finalTotal": 16250.00
}
```

### Pricing rules (AD)

| Method | Path | Body |
|---|---|---|
| GET | `/pricing-rules` | – |
| POST | `/pricing-rules` | `{name, ruleType: SEASONAL\|OCCUPANCY, multiplier 0.50-3.00, startDate?, endDate?, minOccupancyPercent?, hotelId?, roomType?, active?}` |
| PUT | `/pricing-rules/{ruleId}` | same |
| DELETE | `/pricing-rules/{ruleId}` | 204 |

### Internal (not routed; JWT + `X-Internal-Api-Key`)

| Method | Path | Body | Purpose |
|---|---|---|---|
| POST | `/internal/holds` | `{roomId, checkIn, checkOut, guests}` | lock room, check overlap, price, create HELD hold → `HoldResponse` |
| POST | `/internal/holds/{holdId}/confirm` | – | HELD → CONFIRMED (409 if expired/released) |
| POST | `/internal/holds/{holdId}/release` | – | → RELEASED (idempotent) |

## BOOKING-SERVICE (`/api/bookings/**` → `lb://BOOKING-SERVICE`)

| Method | Path | Access | Body | Response |
|---|---|---|---|---|
| POST | `/bookings` | U | `{roomId, checkIn, checkOut, numberOfGuests}` | 201 `BookingResponse` (PENDING) |
| POST | `/bookings/{bookingId}/pay` | U (owner) | `{paymentMethod: CARD\|UPI\|NET_BANKING\|WALLET, simulateFailure?: boolean}` | 200 `{booking, paymentStatus, transactionReference, message}`; **202** with `paymentStatus: "UNKNOWN"` when the Payment Service did not answer in time (booking stays `PAYMENT_IN_PROGRESS`, resolved automatically) |
| POST | `/bookings/{bookingId}/cancel` | O | – | `BookingResponse` |
| GET | `/bookings/my` | U | – | `BookingResponse[]` |
| GET | `/bookings/{bookingId}` | O | – | `BookingResponse` |
| GET | `/bookings` | AD | query `status?` | `BookingResponse[]` |

`bookingStatus` is one of `PENDING`, `PAYMENT_IN_PROGRESS`, `CONFIRMED`, `CANCELLATION_PENDING`, `CANCELLED`
(see `docs/review-1/09-booking-workflow.md`). The two intermediate states settle on their own; a guest can neither
pay nor cancel a booking while it is in one of them (409).

`BookingResponse`: `{bookingId, userId, roomId, hotelId, hotelName, roomNumber, roomType, checkIn, checkOut, nights,
numberOfGuests, totalAmount, currency, bookingStatus, statusReason, paymentDueBy, paymentId, paymentReference,
createdAt, updatedAt}`

## PAYMENT-SERVICE (`/api/payments/**` → `lb://PAYMENT-SERVICE`, read-only via gateway)

| Method | Path | Access | Response |
|---|---|---|---|
| GET | `/payments/my` | A | `PaymentResponse[]` |
| GET | `/payments/{paymentId}` | O | `PaymentResponse` |
| GET | `/payments/booking/{bookingId}` | O | `PaymentResponse[]` |
| GET | `/payments` | AD | `PaymentResponse[]` |

Internal (booking-service only; never routed by the gateway; `X-Internal-Api-Key` required):
`POST /internal/payments {bookingId, amount, method, simulateFailure}` (idempotent per booking),
`GET /internal/payments/booking/{bookingId}` (charged payment, else latest failed attempt, else 404 – used to resolve a lost
response), `POST /internal/payments/{id}/refund` (idempotent).

`PaymentResponse`: `{paymentId, bookingId, userId, amount, currency, method, status, transactionReference,
failureReason, createdAt, refundedAt}`

## Infrastructure

| URL | Purpose |
|---|---|
| `http://localhost:9000` | Eureka dashboard |
| `http://localhost:8080/actuator/health` | Gateway health (public) |
| `http://localhost:808x/actuator/health` | Service health (direct) |
