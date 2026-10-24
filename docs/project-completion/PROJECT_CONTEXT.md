# GrandVista – Project Context

A self-contained description of the system as it stands after the completion work, written so that someone without the
repository (or the lost conversation) can understand it. Everything here was read from the code or verified by running it;
statements that are design intent rather than verified behaviour are marked *(intent)*.

## 1. Purpose and business problem

**GrandVista Hospitality Inventory Control & Dynamic Room Reservation Platform** – a university SOA / microservices project
(KL University). A fictional multi-property hotel chain needs to:

1. keep an inventory of hotels and rooms,
2. price rooms dynamically (season and demand),
3. let guests search, hold, pay for and cancel reservations,
4. **never double-book** a room, even under simultaneous requests,
5. keep payment, reservation and inventory consistent although they live in different services and databases,
6. secure everything with JWT roles (guest / administrator).

Payments are **simulated** (a deterministic mock gateway); no real money or card data is involved.

## 2. Technology stack

| Layer | Technology |
|---|---|
| Language / build | Java 25, Maven 3.9 (six independent Maven projects, no parent pom) |
| Framework | Spring Boot 4.0.8, Spring Cloud 2025.1.3 (Eureka, Gateway Server WebFlux, LoadBalancer) |
| Security | Spring Security 7, OAuth2 resource server (Nimbus, **HS256** shared secret), BCrypt |
| Persistence | Spring Data JPA / Hibernate 7, PostgreSQL 18 (one server, four databases), `ddl-auto=update` |
| Inter-service calls | Spring `RestClient` + `DeferringLoadBalancerInterceptor` (client-side load balancing via Eureka) |
| API docs | springdoc-openapi 3.0.3 (Swagger UI per business service) |
| Frontend | React 19.3, React Router 7.18, Vite 8.3; tests with Vitest 5, Testing Library, jsdom |
| Tests | JUnit 5, Mockito, MockMvc, WebTestClient, H2 (in-memory) and real PostgreSQL for concurrency |
| Deployment | native (Maven / npm) and Docker Compose (written, **not executed** – no Docker on the dev machine) |

Versions were chosen and verified earlier by the project owner (Java 25 because the machine has only JDK 25; Boot 4.0.x because
no stable Spring Cloud train supports Boot 4.1 yet). They were not changed.

## 3. Architecture

```
 React (Vite) :5173 ──Bearer JWT──►  API GATEWAY :8080   routing · JWT + role pre-check · CORS · X-Request-Id
                                       │ lb://AUTH-SERVICE   lb://ROOM-SERVICE   lb://BOOKING-SERVICE   lb://PAYMENT-SERVICE
                                       ▼
        AUTH :8081        ROOM :8082 (+ :8085)            BOOKING :8083               PAYMENT :8084
        users, JWT        hotels, rooms, holds,           booking lifecycle,          simulated payments,
                          pricing, availability           saga + recovery job         refunds
        grandvista_       grandvista_room_db              grandvista_booking_db       grandvista_payment_db
        auth_db
                      booking ──(RestClient, load-balanced, JWT + X-Internal-Api-Key)──► room /internal/holds/**
                      booking ──────────────────────────────────────────────────────► payment /internal/payments/**
                      EUREKA :9000 – every service registers and discovers the others
```

| Service | Port | Responsibility |
|---|---|---|
| eureka-server | 9000 | service registry and dashboard |
| api-gateway | 8080 | the only entry point for browsers/Postman; blocks `/internal/**`, `/eureka/**`, other actuator endpoints; strips `X-Internal-Api-Key`; CORS for `http://localhost:5173`; retries idempotent GETs to room-service |
| auth-service | 8081 | register (always `ROLE_USER`), login, JWT issue, admin bootstrap, role management |
| room-service | 8082 | hotels, rooms (soft delete), date-aware holds, availability, dynamic pricing, hold expiry job, demo seeder; a second instance on 8085 demonstrates load balancing (`X-Instance-Id` response header) |
| booking-service | 8083 | booking lifecycle and the Booking → Payment → Room orchestration, expiry job and **recovery job** |
| payment-service | 8084 | simulated payment and refund, idempotent per booking |
| grandvista-frontend | 5173 | React UI for guests and administrators |

## 4. Gateway routing and access rules

| Gateway path (`/api/...`) | → service | Access |
|---|---|---|
| `auth/register`, `auth/login` | auth | public |
| `auth/me` | auth | any logged-in user |
| `auth/users/**` | auth | ADMIN |
| `rooms`, `rooms/{id}`, `/availability`, `/price`, `hotels`, `hotels/{id}` (GET) | room | public |
| `rooms/all`, `rooms/inventory-summary`, `rooms/{id}/holds`, all writes on rooms/hotels, `pricing-rules/**` | room | ADMIN |
| `POST bookings`, `POST bookings/{id}/pay` | booking | USER (admins cannot book) |
| `GET bookings` | booking | ADMIN |
| other `bookings/**` | booking | owner or ADMIN (ownership enforced in the service) |
| `GET payments` | payment | ADMIN |
| other `GET payments/**` | payment | owner or ADMIN |
| anything else | – | denied |

Every service validates the JWT again and enforces its own rules (defence in depth); bypassing the gateway does not bypass security.

## 5. JWT authentication

* `POST /auth/login` returns `{accessToken, tokenType, expiresAt, user}`. Claims: `iss=grandvista-auth-service`, `sub=<user id>`,
  `email`, `name`, `roles=[ROLE_USER|ROLE_ADMIN]`, `iat`, `exp` (default 60 min), `jti`. Signed HS256 with the shared `JWT_SECRET`.
* Gateway, room, booking, payment (and auth for its own protected endpoints) verify signature, expiry and issuer.
* Passwords are BCrypt hashes. Registration can only create `ROLE_USER`; the first administrator is created from configuration
  at start-up, further administrators are promoted by an administrator.
* The frontend keeps the token in `localStorage`, attaches it to API calls (but **not** to login/register), logs out on a 401
  for an authenticated request and when the token's `exp` is reached.
* Internal endpoints (`/internal/**` on room and payment) additionally require `X-Internal-Api-Key` (constant-time compare).

## 6. Inter-service communication

Booking Service calls Room and Payment Service by their **Eureka names** (`http://ROOM-SERVICE`, `http://PAYMENT-SERVICE`);
the load-balancer interceptor picks a registered instance round-robin. The guest's JWT is forwarded together with the
internal key so downstream services know *who* the guest is. Failures are translated: business 4xx errors keep their status,
unreachable / timeout / 5xx become 503, and an ambiguous payment outcome is handled by the saga (section 7).

## 7. Booking and payment workflow

```
create : Room.createHold (row lock, overlap check, price) → save PENDING booking
pay    : claim PENDING → PAYMENT_IN_PROGRESS (committed) → Payment.process (idempotent per booking)
            FAILED          → CANCELLED, hold released
            SUCCESS         → store payment id → Room.confirmHold → CONFIRMED
                                           confirm fails → CANCELLATION_PENDING → refund, then release → CANCELLED
            timeout/outage  → stays PAYMENT_IN_PROGRESS; looked up later, never cancelled blindly (HTTP 202 "UNKNOWN")
cancel : CONFIRMED → CANCELLATION_PENDING → refund FIRST, release room SECOND → CANCELLED
recover: every 30 s – PAYMENT_IN_PROGRESS older than 60 s: ask Payment Service and finish; CANCELLATION_PENDING older than 30 s: retry
```

Full details, guarantees and the documented residual windows: `docs/review-1/09-booking-workflow.md`.

## 8. Database structure

| Database | Tables |
|---|---|
| `grandvista_auth_db` | `users` (unique email, `password_hash`, role) |
| `grandvista_room_db` | `hotels`, `rooms` (→ hotel), `pricing_rules` (SEASONAL: date range · OCCUPANCY: minimum %, optional hotel / room type, multiplier 0.5–3.0), `room_holds` (→ room; user id, check-in, check-out (exclusive), guests, amount, status HELD/CONFIRMED/RELEASED/EXPIRED, `expires_at`; index on room + dates) |
| `grandvista_booking_db` | `bookings` (room/hotel snapshot, `hold_id`, `payment_id`, status PENDING / PAYMENT_IN_PROGRESS / CONFIRMED / CANCELLATION_PENDING / CANCELLED, reason, `version` for optimistic locking) |
| `grandvista_payment_db` | `payments` (booking id, user id, amount, method, status SUCCESS/FAILED/REFUNDED, unique transaction reference, **`success_booking_id` with a unique constraint** = at most one successful charge per booking) |

No cross-database foreign keys: services link records by plain ids. Availability is *derived* from `room_holds`, never from a
flag. Overlap rule (check-out day is free): `existing.check_in < requested.check_out AND existing.check_out > requested.check_in`.

## 9. Dynamic pricing

For each night: `price = base × highest matching SEASONAL multiplier (else 1.00) × highest matching OCCUPANCY multiplier (else
1.00)`, rounded half-up to 2 decimals; the total is the sum of the nights. Occupancy = share of the hotel's ACTIVE rooms with
a blocking hold overlapping the stay. Rules of the same type never compound. The quote shown on the room page, the amount
held, the booking total and the payment amount all come from this one calculation – the amount charged is never supplied
by the browser.

## 10. Frontend

Public: home, login, register, room search (city, hotel, type, guests, max price, dates), room details with availability and price
breakdown. Guest: reserve, payment page (UPI / card / net banking / wallet, "simulate a declined payment" switch, countdown
for the room hold), payment result (confirmed / failed / **verifying** / **pay again**), My bookings with cancel. Administrator:
dashboard (occupancy, booking counts and value), hotels, rooms (create/edit/status/soft-delete/reservations), pricing rules and
base prices, all bookings (status filter, cancel with refund), users (promote/demote). Route guards are UX only; the real
authorization is in the gateway and the services. Bookings in `PAYMENT_IN_PROGRESS` / `CANCELLATION_PENDING` are shown as
"Payment processing" / "Cancelling", cannot be paid or cancelled again, and the pages refresh themselves until they settle.

## 11. Development setup (short)

See DEPLOYMENT_GUIDE.md. In brief: create the four databases, copy `grandvista-local.properties.example`, start Eureka → auth →
room → payment → booking → gateway, then `npm run dev`. Tests: `mvn test` in each service folder and `npm test` in
`grandvista-frontend`; none of them needs PostgreSQL (the optional PostgreSQL concurrency test needs a disposable database –
see TEST_VERIFICATION_REPORT.md).

## 12. Environment facts worth knowing

* The development machine has only JDK 25; PostgreSQL 18 listens on **5433**.
* A Windows user-level `DB_PASSWORD` variable from another project overrides the properties file.
* Antivirus HTTPS interception needs `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`.
* The user runs the stack from Spring Tool Suite with `E:\SOA-Project` as the workspace (`.metadata/` is git-ignored – never delete it).
