# GrandVista Hospitality – Final Project Handover Report

**Project:** GrandVista Hospitality Inventory Control & Dynamic Room Reservation Platform – SOA / microservices university project (KL University)
**Scope of this report:** the completion work done on the repository starting at commit `0218303` ("Stage-1"), written so that an independent technical
reviewer **without repository access** can judge it. Every statement is either (a) read from the code, (b) the result of a command that was run
(numbers are copied from tool output, none are estimated), or (c) explicitly labelled *assumption*, *not tested* or *not performed*.
Companion documents in the same folder: `PROJECT_CONTEXT.md`, `DEVELOPMENT_CHANGELOG.md`, `TEST_VERIFICATION_REPORT.md`, `DEPLOYMENT_GUIDE.md`,
and `verification/` (scripts and raw results).

> No password, token, private key or connection string appears in this report.

---

## A. Executive summary

**Status: FUNCTIONALLY COMPLETE WITH LIMITATIONS** (definition in section P).

The application was already broadly functional at the start (six Spring Boot services, React frontend, 101 passing backend tests). The audit confirmed that,
and also confirmed the five distributed-consistency risks that an earlier audit had reported. The work then:

* **Fixed 8 defects** – 3 high-severity (lost-payment-response without refund; room released before refund on cancellation; an internal-API-key bypass via a
  percent-encoded path), 4 medium, 1 low (section E/F).
* Replaced the booking/payment workflow with a **saga that has persisted recovery states** (`PAYMENT_IN_PROGRESS`, `CANCELLATION_PENDING`), idempotent
  payments with a database-enforced "one successful charge per booking", refund-before-release cancellation, no database lock held across remote calls, and a
  **recovery job** – and proved it by injecting real outages into a running stack.
* Raised automated tests from **101 to 266** (166 backend, 100 frontend – the frontend had none), all passing, none skipped.
* Verified, on a **disposable PostgreSQL instance and stack that never touched your real database**: the Postman collection (49 requests / 72 assertions), 31
  distributed-failure checks, a 56-check security sweep, 34 real-browser (Chrome) journey checks, load balancing (exact 30/30 over two Room Service instances),
  a bounded load test (7 563 requests, 0 server errors) and a database consistency proof (0 overlapping holds, 0 double charges, 287 confirmed bookings = 287
  confirmed holds).
* Added Docker/Compose deployment files, **validated statically only**.

**What is not done / not verified:** the Docker images were **never built and no container was run** (Docker is not installed on the machine); **no cloud
deployment** was performed; performance was measured only on one developer machine at 40 concurrent users, so the "sub-second" goal is *met at p99 in that
setup* but is *not demonstrated at promotional scale*. The original assignment's SOA requirements are therefore satisfied **except** container/cloud
deployment (PARTIAL) and large-scale performance (PARTIAL/NOT VERIFIED) – matrix in section N.

**One thing needs your attention before you start the services against your real database:** the new booking-service and payment-service run two small,
non-destructive schema changes on first start (section I; reviewable SQL in DEPLOYMENT_GUIDE.md §3). Take a `pg_dump` first if you want a restore point.

---

## B. Initial audit findings (what existed before this work)

### B.1 Repository and environment checks (Phase 1)

| Check | Result |
|---|---|
| Git state | clean working tree on `main`, HEAD `0218303` – **PASS** |
| JDK / Node | Java 25.0.3, Node 24.13.1 / npm 11.6.2 – **PASS** |
| Maven | works offline against a populated repository (needs `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` because antivirus re-signs HTTPS) – **PASS** |
| PostgreSQL | a PostgreSQL 18 server (the user's real one) listens on port 5433 – **present, deliberately not touched**; a separate disposable instance on 5543 was used for everything |
| Docker | **not installed** → container execution **BLOCKED / NOT TESTED** |
| Ports 9000, 8080–8085, 5173 | free at the start – **PASS** |
| Environment trap | a Windows *user-level* `DB_PASSWORD` variable overrides the local properties file (documented in README/DEPLOYMENT_GUIDE) |
| Build of the original code, all 6 modules | `mvn test` **101 tests, 0 failures** – **PASS**; frontend `npm run build` – **PASS** |
| Original stack started on the disposable DB | all six services + the second Room Service instance healthy and registered in Eureka – **PASS** |
| Original Newman collection on the original code | 49 requests, 72 assertions, 0 failed – **PASS** |

### B.2 What was already implemented

* **Services:** eureka-server (9000), api-gateway (8080, routes `lb://…`, JWT + role pre-check, CORS, `X-Request-Id`, blocks `/internal/**`), auth-service (8081),
  room-service (8082; hotels, rooms, soft delete, date-aware holds with a pessimistic row lock, availability, rule-based dynamic pricing, demo seeder, schedulers),
  booking-service (8083; booking lifecycle, Booking → Payment → Room orchestration), payment-service (8084; simulated payments and refunds).
* **Frontend:** 8 guest pages and 7 admin pages with route guards.
* **Security:** HS256 JWT, BCrypt, roles, defence in depth (gateway + each service), internal API key for service-to-service endpoints.
* **Docs:** Review-1 documents, Mermaid diagrams, Postman collection, README.
* **Missing:** Docker/Compose, frontend tests, a recovery mechanism for interrupted distributed operations, any live failure testing.

### B.3 The five earlier-reported risks, checked against the code

| # | Reported risk | Verdict at `0218303` |
|---|---|---|
| 1 | Payment response lost/timeout → booking cancelled later without refund | **Confirmed by reading the code** (the old behaviour was not executed): a remote failure threw 503, the transaction rolled the booking back to PENDING, the expiry job later cancelled it while the payment stayed SUCCESS. The *fixed* behaviour was then verified live with the Payment Service stopped. |
| 2 | Cancel releases inventory before refund | **Confirmed.** `releaseHold` then `refund`; a failed refund rolled the booking back after the room had been freed. |
| 3 | DB lock held across slow remote calls | **Confirmed.** `payForBooking` was `@Transactional` with `SELECT … FOR UPDATE` around two remote calls (read timeout 10 s each). |
| 4 | "One successful payment per booking" only an application check | **Confirmed.** `existsByBookingIdAndStatus` – racy. |
| 5 | Distributed operations lack recovery/reconciliation | **Confirmed.** No intermediate states, no job. |

---

## C. Final architecture

```
 React (Vite) :5173 ──Bearer JWT──►  API GATEWAY :8080   routing · JWT + role pre-check · CORS · X-Request-Id
                                       │ lb://AUTH-SERVICE  lb://ROOM-SERVICE  lb://BOOKING-SERVICE  lb://PAYMENT-SERVICE
        AUTH :8081      ROOM :8082 (+:8085)         BOOKING :8083                  PAYMENT :8084
        grandvista_     grandvista_room_db          grandvista_booking_db          grandvista_payment_db
        auth_db
   booking ──RestClient (Eureka names, round-robin, guest JWT + X-Internal-Api-Key)──► room /internal/holds/**, payment /internal/payments/**
   booking recovery job ──(internal key only = trusted "system" principal)──► same internal endpoints
   EUREKA :9000 – every service registers; one PostgreSQL server, four databases, no cross-database foreign keys
```

| Service | Port | Responsibility | Database |
|---|---|---|---|
| eureka-server | 9000 | registry + dashboard | – |
| api-gateway | 8080 | single entry point, edge security, CORS, tracing, retries for GET to room-service | – |
| auth-service | 8081 | register, login, JWT, roles, admin bootstrap | `grandvista_auth_db` |
| room-service | 8082, 8085 | hotels, rooms, holds, availability, pricing | `grandvista_room_db` |
| booking-service | 8083 | booking lifecycle, saga, expiry + recovery jobs | `grandvista_booking_db` |
| payment-service | 8084 | simulated payments and refunds | `grandvista_payment_db` |
| frontend | 5173 | React UI | – |

Stack: Java 25, Spring Boot 4.0.8, Spring Cloud 2025.1.3, Spring Security 7 (HS256), Hibernate 7, PostgreSQL 18, React 19, Vite 8 (versions unchanged from the
project owner's earlier decisions). Full description: `PROJECT_CONTEXT.md`.

---

## D. Features implemented (verified unless marked)

**Guest:** register, login/logout, session expiry handling, room search (city, hotel, type, guests, max price, dates), availability and **dynamic price breakdown**,
reserve (room is held for 15 minutes), pay (UPI / card / net banking / wallet, all simulated; "decline" switch for demos), payment result with states
*confirmed / failed / verifying / cancelling / pay again*, My bookings with cancel (refund for confirmed bookings before check-in).
**Administrator:** dashboard (occupancy, booking counts and value), hotels (create/edit), rooms (create/edit/status/soft delete/reservations), pricing rules and
base prices, all bookings (filter by five statuses, cancel with refund), users (promote/demote).
**Backend:** overlap-free reservations (check-out day free), capacity checks, expired holds stop blocking automatically, seasonal and occupancy pricing (highest
rule per type, never compounded), JWT roles at gateway and service level, internal API for booking → room/payment, Eureka registration, client-side load
balancing, idempotent payments and refunds, saga with persisted recovery states and a recovery job, OpenAPI per service.
**Not features:** real payments, e-mail/SMS notifications, refresh tokens, rate limiting.

---

## E. Problems discovered

| # | Sev | Problem | Where | How found |
|---|---|---|---|---|
| 1 | **High** | Lost/timed-out payment response → booking later cancelled by the expiry job while the guest stays charged | booking, payment | code review; fixed behaviour verified live with the Payment Service stopped |
| 2 | **High** | Cancellation freed the room before the refund; a failed refund left the room bookable by someone else while the first booking stayed CONFIRMED and paid | booking | code review; fixed behaviour verified live with the refund path unavailable |
| 5 | **High** | **Internal-API-key bypass**: `/%69nternal/...` skipped the key filter (it tested the raw URI; MVC and Spring Security use the decoded path) → a JWT-only caller reached internal room/payment endpoints (confirm a hold without paying, create payments with a caller-chosen amount, read any booking's payment). Reachable only by calling a service port directly (the gateway never routes `/internal/**`), so it defeated the second security layer but not the first | room, payment | live probe during the security review: room-service answered 404 "hold not found" (reached the controller) instead of 403; payment-service answered 200 |
| 3 | Med | Database transaction and row lock held across two remote calls | booking | code review |
| 4 | Med | One-successful-charge-per-booking not enforced by the database | payment | code review |
| 8 | Med | The booking status CHECK constraint (created by Hibernate) would reject the new states on any existing database; `ddl-auto=update` never alters constraints | booking | inspected the constraint in a database created by the old code |
| 6 | Med | UI: a failed login while an old session was stored (second tab, mistyped password) was treated as "session expired" and logged the user out everywhere | frontend | real-browser run (a second tab shared the stored session) |
| 7 | Low | UI: max-price field `min=1 step=100` only accepts 1, 101, 201 …, so searching for 9000 was blocked by the browser | frontend | unit test for the search form |

Observations that were **not** changed: requests rejected at the gateway edge carry no `X-Request-Id`; two *dev-only* Vite advisories (Vite 8.3.0, fixed in 8.3.5 –
`npm audit --omit=dev` reports 0 vulnerabilities in production dependencies; the pinned version was left alone on purpose); seeded seasonal pricing rules carry fixed
dates; Swagger UI / OpenAPI endpoints are unauthenticated on every business service (acceptable for an academic project, to be disabled in production).

---

## F. Fixes applied

(Files are listed in section R; regression tests per fix are in `DEVELOPMENT_CHANGELOG.md`.)

| # | Root cause | Solution | Tests |
|---|---|---|---|
| 1, 2, 3 | one big transaction around remote calls; remote failures indistinguishable from "nothing happened"; compensations ordered wrongly | New booking states; **claim** (`PENDING → PAYMENT_IN_PROGRESS`, committed) before the payment call; ambiguous failures leave the booking in progress and trigger a lookup (`GET /internal/payments/booking/{id}`); payment id stored before the room call; cancellation goes `CONFIRMED → CANCELLATION_PENDING` and does **refund first, release second**; a **recovery job** (every 30 s) settles stuck states; every transition re-checks the status under a short row lock; the booking controller answers **202 UNKNOWN** when the outcome is not known yet | `BookingServiceImplTest` (31), live `live_saga.py` (31) |
| 4 | no database guarantee | `payments.success_booking_id` (set to the booking id for a successful charge, kept after a refund) with unique constraint `uk_payments_success_booking`; `processPayment` made idempotent (replay returns the original, other amount → 409, lost race returns the winner) and deliberately non-transactional so the violation can be caught | `PaymentServiceImplTest`, `PaymentRepositoryTest`, live replay checks |
| 5 | filter decided on `getRequestURI()` | decision on the decoded path via Spring's `UrlPathHelper` **and** the servlet path (fail-closed: either says internal); the `/internal/**` authorization rule now requires proof that the key was verified (`requireVerifiedKey()`), so the check can never be skipped by a path the filter does not recognise | `InternalApiKeyFilterTest` ×2, security tests ×2, live probe on both services and through the gateway |
| 6 | 401 on login treated as expired session | login/register never send or react to a stored token | `client.test.js` (red without fix, verified) |
| 7 | HTML `step` base | `step="any"` | `RoomsPage.test.jsx` (red without fix, verified) |
| 8 | Hibernate never alters constraints | `BookingStatusConstraintUpdater` widens the constraint once (idempotent, additive) | verified on a database created by the old code |
| (enabler) | recovery job has no user | Room/Payment treat "valid internal key + no Authorization header" as a *system* principal: it may confirm/release holds, refund and look payments up, but cannot create a hold or a payment; an invalid JWT is still 401 | filter + service + controller tests |

**Intended behaviour changes** (all documented): a repeated pay request returns the original payment instead of 409; cancelling an unpaid booking while Room Service
is down no longer fails with 503 (the hold lapses by itself ≤ 15 min); the booking API exposes two new statuses and may answer 202 to `pay`.

No business rule of inventory or pricing was changed: the new pricing/inventory tests confirmed the intended behaviour.

---

## G. Distributed transaction reliability

There is **no ACID transaction across the three databases and none is claimed.** The workflow is an orchestration saga; local steps commit; intermediate states are
persisted; every remote operation is idempotent; every state transition re-checks the status under a row lock.

| Situation | Behaviour | Verified by |
|---|---|---|
| Payment succeeds | payment id stored → room confirmed → `CONFIRMED` | unit, Newman, UI, load test |
| Payment declined | `CANCELLED`, hold released | unit, Newman, UI |
| **Payment timeout / lost response** | booking stays `PAYMENT_IN_PROGRESS`; one immediate lookup; else HTTP **202 UNKNOWN**; recovery job later: payment SUCCESS → confirm (or refund if the hold meanwhile expired); FAILED → cancel + release; **no attempt recorded → back to `PENDING` if the hold is valid, else cancel**; service still down → untouched, retry | unit (8 cases), live (outage + a charge created while the response was "lost"), UI |
| Payment request rejected (4xx) | nothing charged → back to `PENDING`, error passed on | unit |
| **Duplicate payment requests** | second "Pay" click → 409; replayed internal request → same payment; racing inserts → unique constraint, winner returned | unit, repository, live (3 replays → 1 payment) |
| Room confirmation fails after payment | `CANCELLATION_PENDING` → refund → release → `CANCELLED` | unit, live |
| **Cancellation / refund failure** | `CANCELLATION_PENDING`, **room stays reserved**, retried until success; re-cancel is an idempotent retry | unit, live (room still reserved during the outage, free only after the refund) |
| Concurrent bookings of one room | row lock in Room Service; overlap query; 1 winner | H2 test, **real-PostgreSQL tests (16 threads → 1)**, live (12 → 1), load (40 → 1) |
| Expired holds | availability query ignores expired HELD rows; housekeeping jobs mark them | repository/unit tests (**not** live – see O) |
| Two Room Service instances | both share the database; the lock lives in PostgreSQL | live (bookings load-balanced across both) |
| Cancel/pay racing | cancel while `PAYMENT_IN_PROGRESS` → 409; pay after cancel → 409 | unit, live |

**Residual windows (honest):** (1) a payment that finishes more than 60 s after it was sent *and* after the recovery job found no attempt could leave a charge on a cancelled
booking (needs a payment 6× slower than the 10 s client timeout; mitigation would be a reconciliation sweep or an outbox); (2) a refund that can never succeed leaves
`CANCELLATION_PENDING` and needs a human; (3) calls are still synchronous (the guest waits) – only locks were removed; (4) a crash of the booking-service *process* mid-payment
was not injected live (the persisted-state design and the outage tests exercise the same recovery code).

---

## H. Security verification

56 live checks passed (details: `TEST_VERIFICATION_REPORT.md` §5.4): JWT **expired / wrong key / foreign issuer / `alg=none` / tampered signature / edited roles / missing** → 401; self-registration
cannot create an admin; guests get 403 on all tested admin endpoints (booking list, payment list, pricing rules, room admin list, inventory summary, room creation); an admin cannot book as a guest; no guest can read, pay or cancel another guest's booking; gateway blocks `/internal/**`, `/eureka/**`,
`/actuator/env`; direct internal calls without the key fail; input validation returns 400/404 (never 500) for bad dates, guests, stay length, malformed JSON and injection-style strings;
error bodies contain no stack traces; CORS allows only the React origin and no credentials; **no secret, password or JWT appears in any service log**.
Authorization is enforced in every backend service (the gateway pre-check is defence in depth), not only in the frontend route guards (also tested).

**Remaining limitations:** HS256 with one shared secret (production: RS256/JWKS); JWT in `localStorage` (XSS exposure); no refresh tokens or revocation; no rate limiting or account lockout; HTTP without TLS;
unauthenticated Swagger UI; the internal API key is a single shared string; seeded demo administrator uses a configured password.

---

## I. Database verification

* **Schema** per service as in `PROJECT_CONTEXT.md` §8. Created from nothing by the final code on an empty PostgreSQL 18 instance (**PASS**) and upgraded from a database created by the old code (**PASS**).
* **Constraints that carry business guarantees:** `uk_payments_success_booking` (one charge per booking), `uk_payments_transaction_reference`, `users.email` unique, `bookings_status_check` (5 statuses), hold overlap enforced by lock + query.
* **Consistency proof after the 7 563-request load run** (whole disposable databases): 0 overlapping blocking holds; 0 bookings with >1 successful charge; 0 CONFIRMED bookings without a SUCCESS payment; 0 SUCCESS payments whose booking is not
  CONFIRMED; 0 CANCELLED bookings holding money; 0 bookings stuck in an intermediate state; 287 CONFIRMED holds = 287 CONFIRMED bookings (booking statuses: CANCELLED 201, CONFIRMED 287, PENDING 4).
* **Schema changes that will run once against your existing database** (additive, reviewable): widen `bookings_status_check`; add nullable `payments.success_booking_id` with a unique constraint. Existing rows are not read or changed
  (existing payments keep `NULL`, which the constraint ignores; an optional backfill is in DEPLOYMENT_GUIDE.md).
* **Your real database was not opened, queried or modified** by any test or script.

---

## J. Automated testing (counts from surefire XML and the Vitest JSON report)

| Module | Before | After | Passed | Failed | Skipped |
|---|---|---|---|---|---|
| eureka-server | 1 | 1 | 1 | 0 | 0 |
| api-gateway | 8 | 8 | 8 | 0 | 0 |
| auth-service | 18 | 18 | 18 | 0 | 0 |
| room-service | 40 | 66 (62 + 4 real-PostgreSQL tests, opt-in) | 66 | 0 | 0 |
| booking-service | 21 | 40 | 40 | 0 | 0 |
| payment-service | 13 | 33 | 33 | 0 | 0 |
| **Backend total** | **101** | **166** | **166** | **0** | **0** |
| grandvista-frontend (Vitest, 15 files) | 0 | 100 | 100 | 0 | 0 |
| **Everything** | 101 | **266** | **266** | **0** | **0** |

Without the opt-in environment variables the 4 PostgreSQL tests are not run (default `mvn test` in room-service reports 62). Per-class breakdown and what each new test proves:
`TEST_VERIFICATION_REPORT.md` §3–4. Frontend production build (`npm run build`): PASS.

---

## K. Runtime and end-to-end testing (disposable stack)

| Suite | Result |
|---|---|
| Postman/Newman (existing collection; Auth, Rooms, Admin, Bookings, Payments, Security demos) | 49 requests, 72 assertions, **0 failed** (before the work, after the saga redesign, and on the final fresh stack) |
| `live_saga.py` – concurrency, duplicates, **payment-service killed and restarted** mid-workflow, cancellation during refund outage | **31 / 31** |
| `security_checks.py` | **56 / 56** (+1 observation) |
| `ui_e2e.mjs` – real Chrome: register/login/search/price/reserve/pay/confirm/cancel/decline, **payment outage shown in the UI and recovered**, tampered token, logout, `/admin` access denied, admin hotel/room/pricing (+50 % rule changes the quote exactly 50 %, delete restores it)/soft-delete/users, mobile layout, no console errors | **34 / 34** |
| Static docker validation | 37 / 37 |

Flows covered end to end: register → login → search → book → pay → confirm; book → declined payment; confirmed → cancel → refund; concurrent reservations; admin inventory change; admin pricing change; payment/room/refund
service failure and recovery. **Not run live:** waiting for a real 15-minute hold to expire; the recovery job with its *default* timers (tests used 15 s / 10 s); a crash of the booking-service process; containers.

---

## L. Load balancing and performance

Setup: one developer machine, load generator on the same machine, one instance of every service except Room Service (two), 40 simulated users.

| Measurement | Result |
|---|---|
| Load balancing, 60 requests | `room-service:8082` × 30, `room-service:8085` × 30 |
| Hot spot, 40 simultaneous bookers of one room/date | 1 × 201, 39 × 409 |
| Read load 15 s (search / availability / quote) | 5 914 requests, 393 req/s, 0 errors; p95 ≈ 182–186 ms, p99 ≈ 230–250 ms, max ≈ 1.0 s |
| Booking workflow (create → pay → sometimes cancel → list) | 1 569 requests, 164 req/s, 0 errors; create p95 437 ms / p99 642 ms; **pay p95 609 ms / p99 749 ms**; cancel p99 647 ms |
| Whole run | 7 563 requests, **0 responses with 5xx or timeout** |

**Sub-second requirement:** every operation's p99 was below 750 ms and the slowest single request was 1.01 s. So it is *met at p99 for 40 concurrent users on this machine*, *not met for the single slowest request*, and **not measured at promotional-traffic scale**,
with a real network, or on production-sized data. No SLA is claimed.

---

## M. Docker and deployment

Created: `docker/service.Dockerfile` (one multi-stage recipe for the six Spring Boot services; non-root runtime; no secrets copied), `docker/frontend.Dockerfile` (Vite build → unprivileged nginx with SPA fallback),
`docker-compose.yml` (PostgreSQL 18 with a named volume and init script that creates the four databases, Eureka, auth, room, payment, booking, gateway, frontend; health checks and `service_healthy` start-up order;
**only gateway :8080, UI :5173 and Eureka :9000 are published** – the business services and the database stay on the internal network; an optional `lb-demo` profile adds a second Room Service on port 8085),
`.env.example` (secrets blank; compose refuses to start until `DB_PASSWORD`, `JWT_SECRET`, `INTERNAL_API_KEY`, `ADMIN_PASSWORD` are set), `.dockerignore` (keeps `grandvista-local.properties` and `.env` out of every image),
`.gitattributes` (LF for container-bound files).
**Executed: nothing in Docker.** Static validation 37/37; the five base-image tags were confirmed to exist. The first `docker compose up --build` on your side is the first real execution (troubleshooting order in DEPLOYMENT_GUIDE.md §4).
Native start-up (Maven jars + disposable PostgreSQL + Vite) was executed repeatedly. **No cloud deployment, no paid resources, nothing pushed anywhere.**

---

## N. Original assignment compliance matrix

| # | Requirement | Status | Source evidence | Test evidence | Remaining limitation |
|---|---|---|---|---|---|
| 1 | Room Service (inventory, holds, pricing) | **COMPLETE** | `room-service/…` (controllers, `InventoryServiceImpl`, `PriceCalculator`, `RoomHold`) | 66 unit/slice/PG tests; Newman; UI | – |
| 2 | Booking Service (lifecycle, orchestration) | **COMPLETE** | `booking-service/…/BookingServiceImpl`, `BookingStatus` | 40 tests; saga 31; UI; load | residual windows (G) |
| 3 | Payment Service | **COMPLETE (simulated)** | `payment-service/…`, `SimulatedPaymentGateway` | 33 tests; saga; load | no real gateway by design |
| 4 | Authentication Service | **COMPLETE** | `auth-service/…` | 18 tests; security sweep | no refresh/revocation/lockout |
| 5 | JWT authentication | **COMPLETE** | `JwtTokenProvider`, per-service `SecurityConfig`, gateway | forgery/expiry matrix (8 live + unit) | HS256 shared secret |
| 6 | Administrator access | **COMPLETE** | role rules at gateway and services; admin pages | security 7 + UI admin journey | – |
| 7 | Room inventory management | **COMPLETE** | room/hotel CRUD, status, soft delete | UI admin journey; Newman | – |
| 8 | Dynamic pricing | **COMPLETE** | `PriceCalculator`, `PricingServiceImpl`, rules CRUD | 17 pricing tests; UI (+50 % rule changes quote exactly) | seeded rule dates expire |
| 9 | Reservation lifecycle | **COMPLETE** | holds, saga, expiry + recovery jobs | unit, live saga, UI | hold expiry not live-tested |
| 10 | Prevention of overbooking | **COMPLETE and verified on PostgreSQL** | row lock + overlap query (`RoomHoldRepository`) | 16-thread PG test; live 12→1; load 40→1; 0 overlaps in DB | depends on one shared database |
| 11 | Payment integration | **COMPLETE (simulated)** | `PaymentServiceClient`, internal API | saga, load | – |
| 12 | Inter-service communication | **COMPLETE** | `RestClientConfig`, `DownstreamCallExecutor` | clients tests, saga | synchronous |
| 13 | Netflix Eureka registration | **COMPLETE** | all `application.properties` | live: 6 instances UP | – |
| 14 | Spring Cloud API Gateway | **COMPLETE** | `api-gateway/…`, `application.properties` | 8 gateway tests; Newman; security 10 | – |
| 15 | Client-side discovery and load balancing | **COMPLETE and verified** | `lb://` routes, `DeferringLoadBalancerInterceptor` | exact 30/30 split; bookings across both rooms | – |
| 16 | Unit testing | **COMPLETE** | 266 tests | all pass | – |
| 17 | Integration testing | **COMPLETE** | MockMvc/WebTestClient/H2/PostgreSQL; Newman; live suites | 49/72 + 31 + 56 + 34 | live suites are scripts outside CI |
| 18 | Docker / cloud deployment | **PARTIAL** | Dockerfiles, compose, env template | static 37/37 only | **images never built, no container run, no cloud deployment** |
| 19 | Performance under promotional traffic / high-traffic readiness | **PARTIAL – NOT VERIFIED at scale** | indexed queries, row locks, load-balanced rooms, recovery | bounded local load test (40 users, 7 563 requests, 0 errors, p99 < 750 ms) | no scale test, no circuit breaker/rate limiting, single DB server |

---

## O. Unresolved issues and risks

| # | Item | Impact | Suggested handling |
|---|---|---|---|
| 1 | Docker never executed | the container path may need small fixes | run `docker compose up --build`; DEPLOYMENT_GUIDE §4 |
| 2 | No cloud deployment | assignment item "or cloud" unmet | needs your approval/credentials |
| 3 | Residual late-payment window (G-1) | theoretical charge without booking | reconciliation sweep or outbox |
| 4 | Unfinishable refund needs a human (G-2) | booking stays `CANCELLATION_PENDING` | add an alert/admin view |
| 5 | Hold expiry and default recovery timers not exercised live; process-crash not injected | evidence gap, not a known bug | optional live test with `HOLD_MINUTES=1` |
| 6 | Performance only locally | cannot claim a production SLA | test on target hardware |
| 7 | First start schema changes on the real DB | additive but real | review SQL / take a dump (DEPLOYMENT_GUIDE §3) |
| 8 | Dev-only Vite advisories (8.3.0 → 8.3.5) | local dev server only | your call: patch-level bump (ask-first per your version rule) |
| 9 | Security design limits (H) | acceptable for an academic project | RS256/JWKS, httpOnly cookies, rate limiting, TLS |
| 10 | Seeded pricing rules have fixed dates | seasonal demo pricing disappears later | edit rules in the admin UI |
| 11 | Edge-rejected requests have no `X-Request-Id` | tracing gap for 401/403 | make the tracing filter run before security |
| 12 | Live suites are scripts, not part of Maven/npm CI | evidence is reproducible but manual | wire into CI |
| 13 | Swagger UI unauthenticated | information disclosure on a real deployment | disable or protect in production |
| 14 | A user-level `DB_PASSWORD` Windows variable overrides the properties file | confusing login failures | remove it (README §6) |

---

## P. Final project status

**FUNCTIONALLY COMPLETE WITH LIMITATIONS.**
All functional requirements of the original assignment are implemented, and the correctness-critical ones (no double booking, no double charge, no lost money on failures, role-based security) were *demonstrated by running them*, including under injected failures and
concurrency, on a real PostgreSQL. The label is not "COMPLETE AND VERIFIED" because two requirements are only partly met: containerised/cloud deployment (files written and statically validated, never executed) and performance under promotional traffic
(measured at small scale only). It is not "PARTIALLY COMPLETE" because nothing functional is missing and nothing known is broken.

---

## Q. Recommended next steps

**Necessary:** (1) run `docker compose up --build` and fix whatever the first real container run shows; (2) review/apply the two schema changes on your real database (with a dump) before starting the new services against it; (3) decide on the dev-only Vite patch bump; (4) re-run the Review-1 demo
using `docs/review-1/10-review-demonstration-guide.md` plus the new "payment outage" demo in README §11.
**Optional:** reconciliation sweep for the residual window; admin view for stuck bookings; run the live suites in CI; Flyway migrations instead of `ddl-auto=update`; circuit breakers and rate limiting; RS256/JWKS; disable or protect Swagger UI; a scale test on target hardware;
wait for the Review-2 rubric before adapting anything to it (nothing here guesses it).

---

## R. Git and file change summary

* **Starting commit:** `02183035c45f3bcfc422702d93a1e12c987f9188` ("Stage-1"), branch `main`, clean tree.
* **Ending HEAD:** `02183035c45f3bcfc422702d93a1e12c987f9188` – **unchanged. No commit, no push, no history rewrite, no branch, no remote operation was performed.**
* **Working tree:** 48 modified tracked files (2 639 insertions, 265 deletions), 67 new untracked files, **0 deleted files**.
* **Existing local databases:** preserved. No service, test or script was ever configured with the real database (port 5433); everything ran on a separate PostgreSQL server (port 5543) with its own data directory and random password. The only future effect on your database is the additive schema change described in I.
* **Also not touched:** `grandvista-local.properties` (read only to scan for leaks, never printed), `.metadata/` (Spring Tool Suite workspace), the Windows `DB_PASSWORD` variable.

**Modified (48):**
backend – booking-service: `client/DownstreamCallExecutor`, `client/PaymentServiceClient`, `config/BookingProperties`, `controller/BookingController`, `entity/Booking`, `entity/BookingStatus`, `repository/BookingRepository`, `service/BookingService`, `service/impl/BookingServiceImpl`, `application.properties`, test `BookingServiceImplTest`;
payment-service: `config/SecurityConfig`, `controller/InternalPaymentController`, `entity/Payment`, `repository/PaymentRepository`, `security/InternalApiKeyFilter`, `service/PaymentService`, `service/impl/PaymentServiceImpl`, `util/AuthenticatedUser`, tests `PaymentSecurityTest`, `PaymentRepositoryTest`, `PaymentServiceImplTest`;
room-service: `config/SecurityConfig`, `controller/InternalHoldController`, `security/InternalApiKeyFilter`, `service/impl/InventoryServiceImpl`, `util/AuthenticatedUser`, tests `RoomSecurityTest`, `PriceCalculatorTest`, `InventoryServiceImplTest`;
frontend – `package.json`, `package-lock.json`, `vite.config.js`, `src/api/client.js`, `src/components/common.jsx`, `src/pages/MyBookingsPage.jsx`, `PaymentPage.jsx`, `PaymentResultPage.jsx`, `RoomsPage.jsx`, `admin/AdminBookingsPage.jsx`, `admin/AdminDashboardPage.jsx`, `src/styles/app.css`;
docs – `README.md`, `docs/api/api-reference.md`, `docs/architecture/architecture-diagrams.md`, `docs/database/02-schema-reference.sql`, `docs/review-1/08-database-design.md`, `docs/review-1/09-booking-workflow.md`.

**Created (67):**
backend – `BookingStatusConstraintUpdater`, `BookingRecoveryScheduler`; tests `InternalApiKeyFilterTest` (room, payment), `ConcurrentHoldPostgresIntegrationTest`, `PricingServiceImplTest`;
frontend – `src/utils/bookingStatus.js`, `src/utils/usePolling.js`, `src/test/setup.js`, `src/test/helpers.jsx`, 15 test files (`App`, `client`, `tokenStorage`, `AuthContext`, `ProtectedRoute`, `LoginPage`, `RegisterPage`, `RoomsPage`, `RoomDetailsPage`, `MyBookingsPage`, `PaymentPage`, `PaymentResultPage`, `bookingStatus`, `AdminBookingsPage`, `AdminDashboardPage`);
deployment – `docker-compose.yml`, `docker/service.Dockerfile`, `docker/frontend.Dockerfile`, `docker/nginx.conf`, `docker/postgres/init-databases.sh`, `.env.example`, `.dockerignore`, `.gitattributes`;
documentation – `docs/project-completion/{PROJECT_CONTEXT,DEVELOPMENT_CHANGELOG,TEST_VERIFICATION_REPORT,DEPLOYMENT_GUIDE,FINAL_PROJECT_HANDOVER_REPORT}.md` and `docs/project-completion/verification/` (10 scripts/helpers, `README.md`, `package.json`, `package-lock.json`, `results/` with 9 evidence files and 7 screenshots).
(Git-ignored build output – `target/`, `dist/`, `node_modules/` – was regenerated and is not part of the change.)

---

## S. Reproducibility commands

```bash
# 0. unit / integration tests (no database needed)
for m in eureka-server api-gateway auth-service room-service booking-service payment-service; do (cd $m && mvn -o test); done       # 162 tests (+4 opt-in)
cd grandvista-frontend && npm install && npm test && npm run build                                                            # 100 tests + build

# 1. disposable stack (own PostgreSQL on 5543, random secrets outside the repo) – Git Bash, JDK 25, Maven, PostgreSQL binaries, Node, Chrome
bash docs/project-completion/verification/start-isolated-stack.sh
source "${GV_WORK_DIR:-${TMPDIR:-/tmp}/grandvista-verification}/env.sh"

# 2. real-PostgreSQL concurrency test (room-service; env vars come from env.sh)
(cd room-service && mvn -o test -Dtest=ConcurrentHoldPostgresIntegrationTest)                                                 # 4 tests

# 3. live suites
npx newman run postman/GrandVista.postman_collection.json -e postman/GrandVista-Local.postman_environment.json --env-var "adminPassword=$ADMIN_PASSWORD"
python docs/project-completion/verification/live_saga.py
python docs/project-completion/verification/security_checks.py
python docs/project-completion/verification/load_test.py
(cd grandvista-frontend && npm run dev) &                                                                                     # then:
(cd docs/project-completion/verification && npm install && node ui_e2e.mjs)

# 4. static checks
(cd docs/project-completion/verification && node check_compose.mjs)
python docs/project-completion/verification/secret_scan.py

# 5. Docker (NOT yet executed by the author)
cp .env.example .env   # fill in the four secrets
docker compose up --build -d && docker compose ps

# 6. clean up the disposable stack
bash docs/project-completion/verification/stop-isolated-stack.sh
```

---

## T. Evidence appendix

### T.1 Test summaries
* Backend (surefire XML → `verification/results/backend_results.json`): eureka 1, gateway 8, auth 18, room 66, booking 40, payment 33 = **166, 0 failures, 0 errors, 0 skipped**.
* Frontend (`frontend_vitest_summary.json`): 15 files, **100 / 100**.
* Newman (`newman_summary.txt`): requests 49/0 failed, assertions 72/0 failed, average response 123 ms.
* Live suites: `live_saga_results.json` (31/31), `security_results.json` (56/56), `ui_e2e_results.json` (34/34), `load_test_results.json`.

### T.2 Selected behaviours as observed through the gateway (identifiers shortened, values illustrative of the structure)
```
POST /api/bookings/{id}/pay      while the Payment Service is stopped
 → 202 {"booking":{"bookingStatus":"PAYMENT_IN_PROGRESS",…},"paymentStatus":"UNKNOWN","transactionReference":null,
        "message":"We could not confirm your payment yet. Please do not pay again - your booking will be updated automatically…"}
POST /api/bookings/{id}/cancel   while booking is PAYMENT_IN_PROGRESS      → 409 "Payment for booking … is being processed - please check again in a moment"
POST /api/bookings/{id}/cancel   confirmed booking, refund unavailable     → 200 {"bookingStatus":"CANCELLATION_PENDING",…}   (room still reserved)
… after the Payment Service returns (recovery job)                         → {"bookingStatus":"CANCELLED","statusReason":"Cancelled by guest - payment refunded"}
12 concurrent POST /api/bookings (same room and dates)                     → one 201, eleven 409
POST /internal/payments (replayed 3×, same booking, same amount)          → the same paymentId each time
GET  http://<payment-service>/%69nternal/payments/booking/1  (JWT, no key) → 403   (was 200 before fix 5)
POST /api/auth/login with a stale token in storage + wrong password (UI)   → error shown, session kept   (was: logged out)
```

### T.3 Configuration references
Recovery timers: `booking-service/src/main/resources/application.properties` → `grandvista.booking.payment-recovery-after=60s`, `grandvista.booking.cancellation-retry-after=30s`.
Eureka / ports / secrets: each service's `application.properties` (secrets only via environment variables or the git-ignored `grandvista-local.properties`).
Compose: `docker-compose.yml`, `.env.example`. Gateway rules: `api-gateway/src/main/java/com/grandvista/apigateway/config/GatewaySecurityConfig.java`.

### T.4 Key source files for the reliability work
`booking-service/src/main/java/com/grandvista/bookingservice/service/impl/BookingServiceImpl.java` (saga, recovery) · `…/entity/BookingStatus.java` · `…/service/BookingRecoveryScheduler.java` · `…/config/BookingStatusConstraintUpdater.java` ·
`payment-service/…/service/impl/PaymentServiceImpl.java` · `…/entity/Payment.java` · `*/security/InternalApiKeyFilter.java` (room, payment) · `room-service/…/service/impl/InventoryServiceImpl.java` · `…/repository/RoomHoldRepository.java` (overlap rule) ·
`grandvista-frontend/src/pages/PaymentResultPage.jsx`, `src/utils/bookingStatus.js`, `src/api/client.js`.

### T.5 Verification process notes
The real-browser suite needed about a dozen runs; the failures before the final run were script defects (timing, a coordinate click on a sticky navbar, an incorrect redirect expectation) plus the two real UI defects listed in E; each was fixed at its cause, not hidden by retries.
One Newman run early on failed because it was started while booking-service was still booting (harness ordering). Nothing was skipped, disabled or weakened to obtain a pass; the one test I changed deliberately is `bookingCannotBePaidTwice`, replaced by tests of the new idempotent contract.
