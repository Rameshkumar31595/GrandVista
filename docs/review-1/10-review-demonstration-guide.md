# 10 – Review-1 Demonstration Guide

A step-by-step script (≈ 20 minutes) mapped to the four Review-1 rubrics. Commands assume the services run on
their default ports. Postman requests are in `postman/GrandVista.postman_collection.json`.

## 0. Before the review (checklist)

- [ ] PostgreSQL running; four databases exist (`docs/database/pgadmin-setup.md`).
- [ ] `grandvista-local.properties` filled (DB user/password, `ADMIN_PASSWORD`).
- [ ] `mvn test` passes in each service (optional – shows test evidence).
- [ ] Start in this order, waiting for "Started …Application" each time:
  1. `eureka-server` → 2. `auth-service` → 3. `room-service` → 4. `payment-service` → 5. `booking-service`
  → 6. `api-gateway` → 7. `grandvista-frontend` (`npm run dev`).
- [ ] Postman: import collection + environment, select *GrandVista Local*.
- [ ] Browser tabs: <http://localhost:9000>, <http://localhost:5173>, pgAdmin4.

---

## Rubric 1 – Problem analysis & requirements (≈ 3 min)

1. Show `docs/review-1/01-problem-analysis.md` – problems P1–P7 and the problem → solution table.
2. Show `02-functional-requirements.md` – FR ids with acceptance criteria **and the implementing class**.
3. Show `03-non-functional-requirements.md` – including the honest academic-vs-production table.
4. Show the booking sequence diagram in `docs/architecture/architecture-diagrams.md` (C).

**Talking point:** "Availability is not a boolean – it is computed from date-range holds, and the insert is protected
by a row lock."

## Rubric 2 – Microservices & service discovery (≈ 5 min)

1. Open the **Eureka dashboard** <http://localhost:9000> → *Instances currently registered*: API-GATEWAY,
   AUTH-SERVICE, BOOKING-SERVICE, PAYMENT-SERVICE, ROOM-SERVICE.
2. In STS / file explorer show six independent Maven projects and four databases in pgAdmin.
3. Show `booking-service/.../config/RestClientConfig.java` (LoadBalancer interceptor, `http://ROOM-SERVICE`) and gateway
   `uri: lb://ROOM-SERVICE`. No `localhost:8082` anywhere in service-to-service code.
4. **Load balancing:** start a second Room Service (`--server.port=8085`, see `05-service-discovery.md` §7).
   Refresh Eureka → ROOM-SERVICE has 2 instances. Run Postman *2. Rooms → List rooms* several times. The
   response header **`X-Instance-Id`** alternates between `room-service:8082` and `room-service:8085`, and each
   console logs `room-service:<port> served GET /rooms`.
5. **Failure isolation:** stop Payment Service, try to pay → clean `503 PAYMENT-SERVICE is currently unavailable`;
   browsing rooms still works. Restart it.

## Rubric 3 – JWT authentication (≈ 6 min)

Postman folder **1. Auth** and **6. Security demos**:

| # | Request | Expected |
|---|---|---|
| 1 | Register user (random email) | 201, `role: ROLE_USER` |
| 2 | Register with `"role":"ROLE_ADMIN"` injected | 201, still `ROLE_USER` |
| 3 | Register invalid (bad email, short password) | 400 with `validationErrors` |
| 4 | Login user | 200, `accessToken` (saved to `userToken`) |
| 5 | Login wrong password | 401 "Invalid email or password" |
| 6 | Login admin | 200, saved to `adminToken` |
| 7 | Paste the user token into <https://jwt.io> | shows `sub`, `roles`, `exp`, `iss`; *signature invalid* without the secret |
| 8 | `GET /api/bookings/my` without token | **401** JSON "Authentication required…" |
| 9 | same with malformed token | **401** "JWT token is invalid or malformed" |
| 10 | same with a token whose payload was edited (role changed) | **401** – signature check fails |
| 11 | USER token → `POST /api/rooms` | **403** |
| 12 | ADMIN token → `POST /api/rooms` | 201 |
| 13 | USER token → another user's booking id | **403** (ownership) |
| 14 | Direct call bypassing gateway: `GET http://localhost:8083/bookings/my` without token | **401** – services validate too |

pgAdmin: `SELECT email, role, password_hash FROM users;` → BCrypt hashes, no plain passwords.

Expired token: set `JWT_EXPIRATION_MINUTES=1` for auth-service, log in, wait 60 s → 401 "JWT token has expired";
the React app redirects to login automatically.

## Rubric 4 – API Gateway (≈ 4 min)

1. Show the routing table in `07-api-gateway.md` and `api-gateway/src/main/resources/application.properties`.
2. `GET http://localhost:8080/api/hotels` → routed to Room Service (`/hotels`, prefix stripped). Gateway console:
   `[request-id] GET /api/hotels -> route=room-service status=200 OK 35ms`; response header `X-Request-Id`.
3. `POST http://localhost:8080/internal/holds` (with user token) → **403** – internal APIs are not exposed.
4. `GET http://localhost:8080/eureka/apps` → **403**.
5. **CORS:** in the React app (<http://localhost:5173>) open DevTools → Network → any API call → response header
   `Access-Control-Allow-Origin: http://localhost:5173`. Postman *6. Security demos → CORS preflight from unknown
   origin* → 403.
6. Show `GatewaySecurityIntegrationTest` passing (`mvn test` in `api-gateway`).

## End-to-end business demo (≈ 5 min)

### Guest (React)

1. Register → login → **Rooms** → city *Hyderabad*, pick dates → only free rooms listed.
2. Open Room 203 → **Check availability & price** → breakdown (base, occupancy, multipliers, rules, total).
   Choose dates between 15 Oct and 15 Nov to show the festive multiplier.
3. **Reserve** → payment page with countdown → pay with UPI → **Booking confirmed** with transaction reference.
4. **My bookings** shows CONFIRMED.

### Collision

5. In a second browser (private window) register another guest; try the **same room and overlapping dates** →
   error *"Room 203 is already reserved for some of the selected dates"*. Choose check-in on the first guest's
   check-out day → allowed.
6. Postman *4. Bookings → Overlapping booking (expect 409)* shows the same via API.

### Payment failure

7. Book another room, tick **"Demo: simulate a declined payment"** → result *Booking not confirmed*, status
   CANCELLED; the same dates are immediately bookable again (room released).

### Admin

8. Login as admin → **Dashboard** (occupancy per hotel, booking counts) → **Rooms Admin** → *Reservations* of room 203
   shows the CONFIRMED hold → **Add room** → **Pricing**: change a base price; add an OCCUPANCY rule; re-check a
   quote to see the new price → **Bookings**: all bookings, cancel one (refund) → **Users**: promote a user.

### Database evidence (pgAdmin, `docs/database/03-useful-queries.sql`)

9. `room_holds` (HELD/CONFIRMED/RELEASED), `bookings` with `status_reason`, `payments` with SUCCESS/FAILED/REFUNDED.

## Likely viva questions – short answers

| Question | Answer |
|---|---|
| Why not one database? | Independent deployability and ownership; a service's schema is private. Cross-service data travels via REST. |
| How do you stop double booking with two Room Service instances? | Database row lock (`FOR UPDATE`) – shared by all instances; proved by concurrent test. |
| What if payment succeeds but room confirmation fails? | Compensation: refund payment, cancel booking (saga). |
| Why validate JWT in services if gateway already does? | Defence in depth; services can be reached directly; ownership rules need the user id. |
| Why HS256? Limitation? | Simple shared secret for a closed system; production would use RS256 + JWKS. |
| How is the first admin created? | `AdminInitializer` from `ADMIN_EMAIL`/`ADMIN_PASSWORD` config, only if no admin exists. |
| Where is load balancing? | `lb://` routes in gateway and the load-balanced `RestClient` in Booking Service (Spring Cloud LoadBalancer, round-robin). |
| Why RestClient not Feign? | Current Spring-recommended client, explicit headers/timeouts/error handling, no extra framework. |
| How is price calculated? | Per night: base × highest seasonal multiplier for that date × highest occupancy multiplier; rules are admin data. |
