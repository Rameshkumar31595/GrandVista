# GrandVista – Development Changelog (completion work)

Starting point: commit `0218303` ("Stage-1"). **Nothing was committed or pushed**; every change below is an uncommitted edit in the
working tree. Severity: **H**igh / **M**edium / **L**ow. "Verified" means the result was observed by running it, not just
by reading the code.

## Summary of defects fixed (8)

| # | Sev | Defect | Where | Found by |
|---|---|---|---|---|
| 1 | H | A payment whose response was lost/timed out left the booking PENDING; the expiry job then cancelled it while the guest had been charged (no refund) | booking / payment | code review (the old behaviour was read from the code, not run); the *fixed* behaviour was then verified live with the payment service stopped |
| 2 | H | Cancelling a confirmed booking released the room *before* the refund; a failed refund left the room free while the booking stayed CONFIRMED and paid | booking | code review; the fixed behaviour was verified live with the refund path unavailable |
| 3 | M | `payForBooking` held a database transaction and row lock across two remote calls (up to 10 s each) | booking | code review |
| 4 | M | "One successful payment per booking" was only an application check (race between two requests) | payment | code review |
| 5 | H | **Internal-API-key bypass**: `/%69nternal/...` (percent-encoded) skipped the key filter, which compared the *raw* URI, while MVC and Spring Security matched the *decoded* path; a JWT-only caller reached internal room/payment endpoints | room, payment | live probe during the security review |
| 6 | M | UI: a failed login while an old session was stored (second tab, mistyped password) was treated as "session expired" and logged the user out everywhere | frontend | real-browser test |
| 7 | L | UI: the "Max base price" field had `min=1 step=100`, so the browser only accepted 1, 101, 201 … and blocked searching for e.g. 9000 | frontend | unit test written for the search form |
| 8 | M | Booking status CHECK constraint would reject the new recovery states on any existing database (`ddl-auto=update` never alters constraints) | booking | verified against a database created by the old code |

Observations that were **not** changed (documented in the handover report): requests rejected at the gateway edge (401/403)
carry no `X-Request-Id`; two dev-only Vite advisories (fixed in 8.3.5) – pinned versions were deliberately left alone;
seeded seasonal pricing rules carry fixed dates.

## Backend

### booking-service

| File | Change |
|---|---|
| `entity/BookingStatus.java` | + `PAYMENT_IN_PROGRESS`, `CANCELLATION_PENDING` (persisted recovery states) |
| `entity/Booking.java` | + `beginPayment()`, `revertToPending()`, `requireCancellation()`, `completeCancellation()` |
| `service/impl/BookingServiceImpl.java` | rewritten: short transactions (`TransactionTemplate`) around remote calls, claim-then-pay, ambiguous payment → lookup → stays in progress, refund-before-release cancellation, `recoverStuckBookings()` |
| `service/BookingService.java`, `service/BookingRecoveryScheduler.java` (new) | recovery job every 30 s |
| `repository/BookingRepository.java` | + `findByStatusAndUpdatedAtBefore` |
| `client/PaymentServiceClient.java` | + `findByBooking` (404 → empty) |
| `client/DownstreamCallExecutor.java` | no JWT in the context (recovery job) → internal key only, instead of throwing |
| `controller/BookingController.java` | pay returns **202** when the outcome is `UNKNOWN` |
| `config/BookingProperties.java`, `application.properties` | `payment-recovery-after=60s`, `cancellation-retry-after=30s` |
| `config/BookingStatusConstraintUpdater.java` (new) | idempotently widens `bookings_status_check` on PostgreSQL |
| tests | `BookingServiceImplTest` rewritten around an in-memory repository: 12 → 31 tests (module total 21 → 40) |

Behaviour change: cancelling an unpaid PENDING booking no longer fails with 503 when Room Service is down – the booking is
cancelled and the hold (which lapses on its own after ≤ 15 min) is released best-effort.

### payment-service

| File | Change |
|---|---|
| `entity/Payment.java` | + `success_booking_id` with `uk_payments_success_booking` (unique, NULL for non-successful rows, stays set after a refund) |
| `service/impl/PaymentServiceImpl.java` | `processPayment` idempotent (replay returns the original; different amount → 409; lost race → winner); + `outcomeForBooking`; system principal cannot pay |
| `controller/InternalPaymentController.java` | + `GET /internal/payments/booking/{id}`; uses `Authentication` so the system principal works |
| `repository/PaymentRepository.java` | + `findFirstByBookingIdAndStatusInOrderByCreatedAtDesc` |
| `security/InternalApiKeyFilter.java`, `config/SecurityConfig.java`, `util/AuthenticatedUser.java` | decoded-path detection (fix 5), `requireVerifiedKey()` authorization rule, `SYSTEM` principal for key-only callers |
| tests | `PaymentServiceImplTest`, `PaymentRepositoryTest`, `PaymentSecurityTest` extended, `InternalApiKeyFilterTest` (new): 13 → 33 |

Behaviour change: a repeated payment request for an already-paid booking now returns the original payment instead of 409
"already paid" (that is what makes retries after a lost response safe).

### room-service

| File | Change |
|---|---|
| `security/InternalApiKeyFilter.java`, `config/SecurityConfig.java`, `util/AuthenticatedUser.java` | same fix and system principal as payment-service |
| `controller/InternalHoldController.java`, `service/impl/InventoryServiceImpl.java` | confirm/release accept the system principal; creating a hold with it is refused |
| tests | `ConcurrentHoldPostgresIntegrationTest` (new, real PostgreSQL, opt-in), `PricingServiceImplTest` (new), `PriceCalculatorTest` +7, `RoomSecurityTest` +3, `InventoryServiceImplTest` +1, `InternalApiKeyFilterTest` (new): 40 → 62 (+4 opt-in) |

No pricing or inventory *business rule* was changed: the new tests confirmed the intended behaviour.

## Frontend (`grandvista-frontend`)

| File | Change |
|---|---|
| `src/utils/bookingStatus.js` (new), `src/utils/usePolling.js` (new) | one place that knows the five statuses, labels, who may cancel; polling hook |
| `src/pages/PaymentResultPage.jsx` | rewritten: confirmed / failed / **verifying** (HTTP 202) / **cancelling** / "payment not completed – pay again"; follows the booking until it settles |
| `src/pages/MyBookingsPage.jsx`, `PaymentPage.jsx`, `admin/AdminBookingsPage.jsx`, `admin/AdminDashboardPage.jsx`, `components/common.jsx`, `styles/app.css` | new statuses: no Pay/Cancel while in flight, auto-refresh, filter lists all five, dashboard counts, badge labels/colours |
| `src/api/client.js` | login/register never send or react to a stored token (fix 6) |
| `src/pages/RoomsPage.jsx` | `step="any"` on the max-price input (fix 7) |
| `package.json`, `package-lock.json`, `vite.config.js` | + `npm test` (Vitest 5.0.5, jsdom 30.1.3, Testing Library react 16.3.3 / dom 10.4.2 / jest-dom 7.0.1 / user-event 14.6.8 – exact versions) |
| 15 test files | 100 tests, see TEST_VERIFICATION_REPORT |

## Deployment files (new)

`docker-compose.yml`, `docker/service.Dockerfile`, `docker/frontend.Dockerfile`, `docker/nginx.conf`,
`docker/postgres/init-databases.sh`, `.env.example`, `.dockerignore`, `.gitattributes` (LF for container-bound files).
Statically validated (37 structural checks); **not built or run** – Docker is not available on the development machine.

## Verification kit (new, `docs/project-completion/verification/`)

Scripts that produced the live evidence, written so that **no password is hard-coded**: `start-isolated-stack.sh` creates a disposable PostgreSQL
(port 5543) and the services with randomly generated secrets stored outside the repository; `live_saga.py`, `security_checks.py`, `load_test.py`,
`ui_e2e.mjs`, `check_compose.mjs`, `secret_scan.py` read everything from environment variables. `results/` holds the outputs of the final runs.
`ConcurrentHoldPostgresIntegrationTest` is the only addition to the normal Maven test tree that needs a database, and it refuses to run unless its
database name ends in `_test_db`.

## Documentation

New: `docs/project-completion/*` (this folder). Updated: `docs/review-1/09-booking-workflow.md` (states, pay, cancel, consistency,
tests), `docs/api/api-reference.md` (statuses, 202, new internal endpoint), `README.md` (tests, Docker, doc index).

## Regression tests added per fix

| Fix | Regression tests |
|---|---|
| 1, 2, 3 | `BookingServiceImplTest`: payment timeout stays in progress; lost response resolved (SUCCESS / FAILED); recovery (5 cases); refund failure keeps the room reserved; refund-before-release order; second pay click / cancel during payment refused. Live: `live_saga.py` (31 checks) |
| 4 | `PaymentServiceImplTest` (replay, different amount, lost race), `PaymentRepositoryTest` (DB rejects a second charge). Live: replayed internal payments → same payment id |
| 5 | `InternalApiKeyFilterTest` ×2 (encoded path, key handling, authorization rule), `PaymentSecurityTest`, `RoomSecurityTest`. Live: probe returns 403 on both services after the fix |
| 6 | `client.test.js` (two tests, red without the fix – verified) |
| 7 | `RoomsPage.test.jsx` (red without the fix – verified) |
| 8 | verified live on a database created by the old code (constraint widened, existing rows untouched) |
