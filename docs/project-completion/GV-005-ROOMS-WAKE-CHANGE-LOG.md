# GV-005 - Rooms 503 when a free-tier service is asleep

Status: merged as PR #6 (main 3638fd5) and deployed to web and gateway. The automatic wake of sleeping services is NOT proven to work live; see "Live result".

## Observed (live, read-only, 2026-10-29 16:34-16:39 IST)
- Rooms page: GET /api/rooms and /api/hotels returned 503 in ~0.34s.
- Room service answered Render's "SERVICE WAKING UP" page, then started in 101s; PostgreSQL connected in ~3s with no errors; /api/rooms then returned 200 with data.
- The gateway routes by Eureka. A sleeping service is evicted from the registry, so the gateway has no instance, answers 503 at once, and nothing wakes the service.
- Not related to the database backup files.

## Change
1. Gateway (KeepWarmService, cloud profile only). Trigger: only the load balancer's "no instance" error (NotFoundException, raised before the request is sent to any service) on an /api/ request. Action: one round of the existing plain health pings to all configured services. Bounds: at most one round per `grandvista.keep-warm.wake-cooldown` (default 60s) and never a second round while the previous one is still running. The failed request is not replayed and the pings carry no client headers. Generic 503 responses, security rejections (401/403), unknown paths and non-/api paths trigger nothing. New property `wake-cooldown` in KeepWarmProperties.
2. Frontend (RoomsPage only). 502/503/504 or no connection on the Rooms search shows "the server may still be waking up" and retries every 10s, at most 18 times (about 3 minutes); then shows "could not be loaded right now ... may be starting up or temporarily unavailable" with a Try again button. Other errors (400/401/403/404/500) show at once with no retry. Retries stop on page close or filter change. The hotel list is fetched again only if it is still missing after rooms load.

## Limits
- The ~100s cold start stays (Render free tier). This starts the wake on the first failed request and hides the wait.
- For /api/auth/** the existing retry spends its ~55s budget first, so the wake fires after that error surfaces.
- Only the Rooms search retries. Other pages show their normal error.

## Tests (local, JDK 25, Maven 3.9.9)
- Gateway 25 pass, 0 fail. New: KeepWarmWakeIntegrationTest runs the real gateway and filter chain (one wake round with no X-Internal-Api-Key or Cookie on pings; no second round while one is running; no wake for 401, unknown path, health), plus unit tests for cooldown, single dispatch, header-free pings.
- Frontend 133 pass on main 64affcc plus this change (includes GV-006 tests); Rooms cases: (Rooms: retry then success, bounded give-up with no claim about the cause, no retry for 401/403/404/500, no timer after unmount, old loop dropped on filter change, hotel failure).
- Screenshots (mobile 412px and desktop 1280px) of the waking state checked.

## Revert
`git apply -R GV-005-rooms-wake.patch` (code, tests, this file) or revert the commit. A live revert needs a redeploy of gateway and web.

## Live result (2026-10-29, after deploy of 3638fd5)
- Deploy: web and gateway both built from 3638fd5 (manual "Deploy latest commit"; Render did not auto-deploy).
- Observed: with Room, Booking and Payment asleep, a Rooms request made the gateway log "No service instance available ... sending health pings". Each ping to Payment, Booking and Room logged "not answering yet (BadGateway)" (an HTTP 502 from the hosting edge, received in under a second).
- None of those services started in the next 5 to 8 minutes. A direct GET to each service's public health URL started each one within about 20 seconds (Room, Payment, Booking boots followed those GETs). After Room registered, /api/rooms returned 200.
- Conclusion: the trigger runs, but the pings did not wake the services in this test. Cause unknown (the 502 body was not logged). Not shown: the request shape, the in-region route or edge behaviour as the cause. Do not rely on this change to wake services until a later test shows a service starting from a gateway ping.
- Still valid: Rooms page bounded retry (unit tests); Eureka, Auth, Room, Booking, Payment cold starts remain (measured: Eureka ~75 s, Room ~100 s).
- Revert: revert PR #6 (3638fd5) and redeploy web and gateway; no env, DB or settings were changed.
