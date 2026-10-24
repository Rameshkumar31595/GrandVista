# GrandVista – Test Verification Report

Every number in this report was produced by a command that was actually run during the completion work; nothing is estimated.
"Disposable stack" = a PostgreSQL server of its own (port 5543, data directory outside the repository, random password) plus
the six services from their jars, created by `docs/project-completion/verification/start-isolated-stack.sh`. **No test in this
report touched your PostgreSQL on port 5433, your `grandvista-local.properties`, or any existing booking/payment/room data.**

Legend: **PASS** = executed and passed · **FAIL** · **NOT TESTED** (reason stated) · **BLOCKED**.

## 1. Summary

| Layer | Result |
|---|---|
| Backend unit / slice / integration tests (JUnit 5, 6 modules) | **166 run, 166 passed, 0 failed, 0 skipped** |
| …of which real-PostgreSQL concurrency tests | 4 passed (opt-in, disposable DB) |
| Frontend tests (Vitest, 15 files) | **100 run, 100 passed, 0 failed, 0 skipped** |
| Frontend production build (`npm run build`) | PASS |
| Postman/Newman collection against the disposable stack | **49 requests, 72 assertions, 0 failed** |
| Live distributed-failure scenarios (`live_saga.py`) | **31 / 31 PASS** (concurrency, idempotency, payment/refund outage and recovery) |
| Live security sweep (`security_checks.py`) | **56 / 56 PASS** (JWT forgery/expiry, roles, IDOR, internal endpoints, validation, CORS, log hygiene) |
| Real-browser journeys, Chrome (`ui_e2e.mjs`) | **34 / 34 PASS** (guest, payment outage in the UI, session, admin, mobile layout) |
| Bounded load test (`load_test.py`) | **7 563 requests, 0 × 5xx/timeouts**; 1 winner among 40 simultaneous bookers; exact 30/30 load balancing; database consistency proof clean |
| Static validation of `docker-compose.yml` and Docker files | 37 / 37 checks PASS |
| **Docker images built / containers run** | **NOT TESTED – Docker is not installed on this machine** |
| Cloud deployment | **NOT PERFORMED** (needs explicit approval and credentials) |
| Secret scan of the repository | no real secret value, JWT or private key found (see §9) |

## 2. Commands

```bash
# backend (each module is an independent Maven project; none needs PostgreSQL except the opt-in test)
cd <module> && mvn -o test
GV_TEST_PG_URL=jdbc:postgresql://localhost:5543/grandvista_room_test_db GV_TEST_PG_USER=… GV_TEST_PG_PASSWORD=… \
  mvn -o test -Dtest=ConcurrentHoldPostgresIntegrationTest      # room-service, disposable *_test_db only
# frontend
cd grandvista-frontend && npm test && npm run build
# live (disposable stack): see docs/project-completion/verification/README.md
```

## 3. Backend automated tests – per module and class (surefire XML is the source)

| Module | Tests | Class (tests) |
|---|---|---|
| eureka-server | 1 | EurekaServerApplicationTests (1) |
| api-gateway | 8 | GatewaySecurityIntegrationTest (8) |
| auth-service | 18 | AuthControllerSecurityTest (6), JwtTokenProviderTest (5), AuthServiceImplTest (4), UserRepositoryTest (2), SecurityErrorWriterTest (1) |
| room-service | 66 | StayPeriodTest (13), RoomSecurityTest (12), PriceCalculatorTest (12), InventoryServiceImplTest (9), InternalApiKeyFilterTest (6), PricingServiceImplTest (5), RoomHoldRepositoryTest (4), **ConcurrentHoldPostgresIntegrationTest (4, real PostgreSQL)**, ConcurrentHoldIntegrationTest (1, H2) |
| booking-service | 40 | **BookingServiceImplTest (31)**, BookingSecurityTest (4), RoomServiceClientTest (3), BookingRepositoryTest (2) |
| payment-service | 33 | **PaymentServiceImplTest (15)**, PaymentSecurityTest (7), InternalApiKeyFilterTest (6), PaymentRepositoryTest (5) |
| **Total** | **166** | 0 failures · 0 errors · 0 skipped |

Before the completion work: 101 tests (1 + 8 + 18 + 40 + 21 + 13). **+65 backend tests.** Without the opt-in PostgreSQL test the
default `mvn test` count of room-service is 62 (the 4 PostgreSQL tests are then reported as not run – they need `GV_TEST_PG_URL`).

What the new tests cover (all passed):

| Area | Tests |
|---|---|
| Saga / reliability (booking) | payment timeout stays `PAYMENT_IN_PROGRESS` and is never cancelled blindly · lost response resolved by lookup (SUCCESS → confirmed, FAILED → cancelled) · recovery job: stuck payment that succeeded, whose hold expired meanwhile (refund), with no payment + valid hold (→ PENDING), no payment + expired hold (→ CANCELLED), payment service still down (untouched) · rejected request returns to PENDING · second pay click and cancel during payment refused · refund **before** release · failed refund keeps the room reserved and is completed later · idempotent re-cancel · ownership |
| Payment idempotency | replay returns the original · replay with another amount rejected · lost race returns the winner · unrelated integrity errors are not swallowed · system principal cannot pay · outcome lookup (charged / failed / none) · **database refuses a second successful charge for a booking** · failed attempts never block · refunded payment keeps its slot |
| Security | **percent-encoded `/%69nternal/...` is still treated as internal** · wrong/missing/near-miss key rejected and never marked verified · valid key without token acts as system · token header not replaced · authorization rule needs the verified key · forged token with key → 401 · internal endpoints closed without the key |
| Pricing | expired and future seasonal rules ignored · season start/end inclusive, check-out night not charged · overlapping rules → highest, never compounded · occupancy threshold exact · half-up rounding, total = sum of rounded nights · scoped rule applies · occupancy derived from real holds (75 % / 50 % / empty / no bookable rooms / capped at 100 %) |
| Overbooking (real PostgreSQL) | 16 simultaneous requests → exactly 1 hold · staggered overlapping windows never overlap · 8 back-to-back stays all accepted · different rooms never block each other |

## 4. Frontend automated tests (Vitest 5, jsdom, Testing Library)

100 / 100 passed in 15 files: App routing and access control (7), API client (12), token storage (4), AuthContext (8), ProtectedRoute (4),
LoginPage (5), RegisterPage (4), RoomsPage (7), RoomDetailsPage (9), MyBookingsPage (9), PaymentPage (10), PaymentResultPage (8),
bookingStatus utilities (6), AdminBookingsPage (5), AdminDashboardPage (2). They cover login/logout/expiry, guards, search
filters, availability + price + reserve, payment methods and declined payment, **payment-outage states (verifying, cancelling, pay
again, polling until settled)**, cancel with refund, admin filters and dashboard counts. Two regression tests were proven to fail
with the bug restored (login token handling, max-price input) before being fixed.

## 5. Integration and end-to-end verification (disposable stack)

### 5.1 The environment

Started from nothing by `start-isolated-stack.sh`: a new PostgreSQL server on port 5543, **empty databases created by the final code**
(so the "create from scratch" path was exercised as well), Eureka, auth, room ×2 (`:8082`, `:8085`), payment, booking, gateway. Eureka listed
`API-GATEWAY`, `AUTH-SERVICE`, `BOOKING-SERVICE`, `PAYMENT-SERVICE`, `ROOM-SERVICE:8082`, `ROOM-SERVICE:8085` – all `UP`. Demo data was seeded
(4 hotels, 16 rooms, 4 pricing rules) and the administrator created. The upgrade path (a database created by the **old** code) was verified
separately earlier in the work: `bookings_status_check` was widened and `uk_payments_success_booking` added, with no data touched.

### 5.2 Postman / Newman – the existing collection (6 folders: Auth, Rooms (public), Admin, Bookings, Payments, Security demos)

| | |
|---|---|
| requests | 49 executed, **0 failed** |
| assertions | 72 executed, **0 failed** |
| average response time | 123 ms (min 6 ms, max 704 ms) |

Run twice on the new code (after the saga redesign and again on the final fresh stack): identical, all green. (One earlier run showed failures because
it was started while booking-service was still booting; it was repeated after the health check and passed – a launch-order mistake of the harness, not a defect.)

### 5.3 Distributed-failure scenarios – `live_saga.py` – **31 / 31 PASS**

| Scenario | Checks (all PASS) |
|---|---|
| **S1 concurrency** (through gateway → booking → 2 room instances) | 12 simultaneous bookings of the same room and dates → **exactly one 201, eleven 409** · back-to-back stay (check-in = previous check-out) accepted · partially overlapping stay by another guest refused |
| **S2 duplicates / idempotency** | two parallel "Pay" clicks → one 200 + one 409 · booking CONFIRMED · exactly one payment row · three replayed internal payment requests return the **same** payment id · still one payment row · replay with another amount → 409 |
| **S3 payment-service outage and recovery** (the Payment Service process is killed and restarted) | pay during outage → **202 UNKNOWN**, booking `PAYMENT_IN_PROGRESS`, not cancelled · cancel while in progress → 409 · second pay click → 409 · cancelling a *confirmed* booking during the outage → `CANCELLATION_PENDING` and **the room is still reserved** · after the restart the recovery job settles everything: booking with no recorded payment → back to `PENDING` · cancelled booking → `CANCELLED`, payment `REFUNDED`, room available **only after** the refund · a charge that "happened but whose response was lost" (created directly in the Payment Service) → booking `CONFIRMED` with exactly one payment · guest can then pay the recovered booking → `CONFIRMED` |
| **S4 cancellation** | healthy cancel → CANCELLED, refunded, room free · cancelling again → 409 · another guest cannot cancel it → 403 |

### 5.4 Security sweep – `security_checks.py` – **56 / 56 PASS**

| Area | Checks |
|---|---|
| Identity (6) | admin login · registration cannot self-assign `ROLE_ADMIN` (extra `role` field ignored) · guest cannot list users · wrong password and unknown user give the same 401 message (no user enumeration) · duplicate email 409 · no password in any response |
| JWT (8) | token minted with the real key accepted (proves the tests are meaningful) · **expired**, **wrong key**, **foreign issuer**, **`alg=none`**, **tampered signature**, **payload edited to ROLE_ADMIN with the old signature**, **no token** → all 401 |
| Roles (7) | a guest gets 403 on: all bookings, all payments, pricing rules, admin room list, inventory summary, room creation · an admin cannot book as a guest (403) |
| Ownership / IDOR (7) | another guest cannot read, pay or cancel someone else's booking (403) · the booking is unchanged afterwards · admin can read any booking · unknown booking 404 · payments of another guest's booking refused or empty |
| Internal endpoints and spoofing (10) | gateway blocks `/internal/**`, `/eureka/**`, `/actuator/env`, `/actuator/heapdump` · direct room-service internal call with a wrong key → 403, with no credentials → 401/403 · `actuator/env` not exposed on ports 8081–8084 |
| Input validation (12) | past check-in, check-out before check-in, zero guests, too many guests, stay > 30 nights → 400 · unknown room → 404 · missing fields → 400 · wrong JSON types → 400 (never 500) · SQL-looking search strings → normal result · registration and empty login validated |
| Error bodies / tracing (3) | 404 body contains no stack trace or class names · unknown gateway route denied · routed responses carry `X-Request-Id` |
| CORS (2) | unknown origin refused · React origin allowed, without credentials |
| Logs (1) | none of the JWT secret, internal key, admin/guest/database passwords or any JWT appears in any service log |

Observation (not a failure, not changed): a request rejected by the gateway's security layer (401/403/CORS) never reaches the routing filter, so it carries
no `X-Request-Id`; routed requests do.

The percent-encoded internal-key bypass found during this sweep is covered in section 3 (unit tests) and was re-probed live after the fix on both services
and through the gateway (all 403; a valid key still works).

### 5.5 Real-browser journeys – `ui_e2e.mjs` (Chrome headless via puppeteer-core, Vite dev server → gateway → services) – **34 / 34 PASS**

| Journey | Checks |
|---|---|
| Guest | register → login (name + GUEST badge shown) · wrong password shows the error and stays · city + date search returns only that city · details keep the searched dates · price breakdown shown · **amount on the payment page equals the quoted total** · pay → "Booking confirmed" with `GV-PAY-…` reference · My bookings lists it · cancel → CANCELLED and "payment refunded" · declined payment → "not confirmed", FAILED, "released" |
| **Payment outage in the real UI** | with the Payment Service stopped: "Verifying your payment … do not pay again" (not "failed") · My bookings shows "Payment processing" with **neither Pay nor Cancel** · after the service is back the page leaves "Payment processing" by itself · booking is `PENDING` again with "Pay now" (no money taken) · guest pays again → confirmed |
| Session | a tampered token ends the session: back on `/login` with an explanation and the token removed · logout clears the session and protects My bookings · a guest typing `/admin` gets "Access denied" |
| Admin | login → dashboard · create hotel · create room (searchable by guests at once) · **add a +50 % seasonal rule → the guest quote rises by exactly 50 % and names the rule; delete it → base price restored** · soft-delete a room (kept as INACTIVE, hidden from guests) · booking list offers all five status filters · promote a guest to administrator and demote again |
| Layout / health | no page scrolls horizontally on a 390 px phone (8 pages) · no uncaught page error or console error in any journey |

**Honest note on this suite.** It was developed against the live system and needed about a dozen runs. Most intermediate failures were defects in the *script*
(clicking before a page finished loading, a coordinate click landing on the sticky navbar, a wrong expectation about the post-login redirect, number-input
clearing, reading the page a moment before a notice rendered) and were fixed in the script. The browser also exposed one **real application defect**: a failed
login while an older session was stored (second tab, mistyped password) logged the user out everywhere; a second one, the max-price search field blocking values
such as 9000, was found while writing the unit tests for the search form. Both were fixed with regression tests that were shown to fail without the fix.
The final scripted run is the one reported above.

## 6. Load balancing and performance

Single developer machine; **the load generator runs on the same machine as the services and PostgreSQL**; every service has one instance except Room Service
(two); 40 simulated users; unauthenticated and authenticated traffic through the gateway. These numbers describe *this setup only* – they are not a
capacity statement and not a proof of any production SLA.

| Measurement | Result |
|---|---|
| **Load balancing** – 60 gateway requests, `X-Instance-Id` header | `room-service:8082` × 30, `room-service:8085` × 30 (exact round-robin) |
| **Hot spot** – 40 users book the same room and dates at the same instant | 1 × 201, 39 × 409 – exactly one winner |
| Read load, 15 s, 40 concurrent users (search, availability, quote) | 5 914 requests, **393 req/s**, 0 errors |
| …search | p50 90 ms · p95 182 ms · p99 250 ms · max 1 008 ms |
| …availability | p50 87 ms · p95 186 ms · p99 250 ms · max 1 010 ms |
| …price quote | p50 90 ms · p95 182 ms · p99 230 ms · max 997 ms |
| Booking workflow, 40 concurrent users (create → pay → sometimes cancel → list) | 1 569 requests in 9.6 s, **164 req/s**, 0 errors; 412 paid, 68 declined, 129 cancelled |
| …create booking (room hold + price + persist) | p50 240 ms · p95 437 ms · p99 642 ms · max 714 ms |
| …pay (booking → payment → room confirm) | p50 302 ms · p95 609 ms · p99 749 ms · max 807 ms |
| …cancel with refund | p50 234 ms · p95 475 ms · p99 647 ms · max 659 ms |
| …my bookings | p50 84 ms · p95 196 ms · p99 249 ms · max 330 ms |
| Whole run | **7 563 requests, 0 responses with 5xx or timeout** |

**Sub-second claim.** In this measured setup the 99th percentile of every operation was below 750 ms, and the slowest single request of the whole run was
1.01 s (a read, early in the run, consistent with JVM warm-up). So the assignment's "sub-second responses" is *met at p99 under 40 concurrent users on this
machine* and *not met for the single slowest request*; it was not measured at promotional-traffic scale, with realistic network latency, or against a
production-sized database, and it is not claimed.

**Consistency proof after the load (queries against the disposable databases, whole database, all earlier test data included):**

| Check | Result |
|---|---|
| pairs of blocking holds that overlap on the same room | **0** |
| bookings with more than one successful charge | **0** |
| CONFIRMED bookings without a payment / without a SUCCESS payment | **0 / 0** |
| SUCCESS payments whose booking is not CONFIRMED | **0** |
| CANCELLED bookings that hold money (paid, neither refunded nor failed) | **0** |
| bookings stuck in `PAYMENT_IN_PROGRESS` / `CANCELLATION_PENDING` | **0** |
| CONFIRMED room holds vs CONFIRMED bookings | **287 = 287** |

## 7. Docker verification

| Item | Status |
|---|---|
| Dockerfiles, compose file, nginx config, PostgreSQL init script, `.dockerignore`, `.env.example` written | done |
| Static validation (`check_compose.mjs`, 37 checks): 8 services, no dependency cycle, every `depends_on` waits for `service_healthy` and the target has a health check, host ports unique and only gateway/UI/Eureka published, health-check ports match each service's configured port, every `${VARIABLE}` documented, secrets blank in `.env.example` and compose refuses to start without them, services' `application.properties` actually read the variables compose sets, image runs as non-root, `.dockerignore` keeps secrets out of the build context, init script creates all four databases with LF endings | **37 / 37 PASS** |
| Base image tags exist on Docker Hub (`maven:3-eclipse-temurin-25`, `eclipse-temurin:25-jre`, `postgres:18-alpine`, `node:24-alpine`, `nginxinc/nginx-unprivileged:stable-alpine`) | confirmed (HTTP 200) |
| `docker build`, `docker compose up`, health checks in containers, Eureka registration in containers, a booking inside the containers | **NOT TESTED – no Docker on this machine** |
| Cloud deployment | **NOT PERFORMED** |

## 8. What was not tested, and known limits of the evidence

* Containers (above). Cloud deployment. Performance at promotional scale or on production-like hardware/network.
* **Expired room holds** are covered by repository/unit tests and by the payment-window logic, **not** by a live test waiting for a 15-minute hold to lapse
  (`HOLD_MINUTES` was not shortened).
* The recovery job was exercised with shortened timers (15 s / 10 s); the shipped defaults (60 s / 30 s) were not exercised live.
* A crash of the Booking Service *process* in the middle of a payment (as opposed to the Payment Service being unavailable) was not injected; the persisted-state
  design is unit-tested and the outage cases above run through the same recovery code.
* The Linux branch of `svc.sh` and the verification kit on non-Windows machines were not executed.
* The browser tests ran against the Vite **dev server**, not the nginx image; Safari/Firefox were not used.
* No test exercises real money, a real bank or a real payment gateway: payments are simulated.
* Existing real data in your PostgreSQL (port 5433) was never opened, queried or modified; the new schema statements will run for the first time when you next
  start booking-service and payment-service against it (reviewable SQL in DEPLOYMENT_GUIDE.md §3).

## 9. Secret scan

`secret_scan.py` searched all 294 tracked and untracked (non-ignored) files for the **values** of the real secrets in your git-ignored
`grandvista-local.properties`, for the random secrets of the disposable stack, for JWTs, private keys and AWS-style keys. **No match.** A looser heuristic for
"something = literal" flagged 13 lines, all reviewed by hand: dummy fixtures in unit tests (`Secret#123`), a test-only key constant that already existed in the
gateway test, ordinary variable assignments in application code, shell variable references (`PGPASSWORD="$DB_PASSWORD"`), and the README's example command.
`.env.example` contains no value for any secret. This report contains no password, token, key or connection string.


## 10. Evidence files

`docs/project-completion/verification/results/`: `backend_results.json` (surefire per class), `backend_mvn_summary.txt`, `frontend_vitest_summary.json`,
`newman_summary.txt`, `live_saga_results.json`, `security_results.json`, `load_test_results.json`, `ui_e2e_results.json`, `secret_scan_output.txt`, and
`screenshots/` (payment page, confirmed, declined, *verifying your payment*, recovered, admin dashboard, mobile). The scripts that produced them are one
directory up; `README.md` there lists the commands.
