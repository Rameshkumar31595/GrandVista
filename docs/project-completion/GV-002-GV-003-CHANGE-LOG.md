# GrandVista fixes GV-002 / GV-003 - change log and revert guide

Date: 2026-10-29 (Asia/Calcutta) - Baseline revision: `f9ae1ef` (origin/main at time of work)
Scope: ONLY the two approved defects. No backup/history/credential/database changes. No commit/push at preparation time.

## What was broken

- **GV-002 (P1):** When no Auth Service instance is registered (free-tier sleep/wake), the gateway
  answered login/register/demo requests instantly with 503 (logs: "No servers available for service:
  AUTH-SERVICE"). The auth route had no recovery handling.
- **GV-003 (P2):** The login page hid the Demo Login tab on ANY failed demo-accounts request
  (including the GV-002 503s), so the tab silently disappeared while the backend was waking up.

## What changed (file by file)

1. `api-gateway/src/main/java/com/grandvista/apigateway/config/AuthRouteConfig.java` **(new)**
   - The auth-service route, moved here from application.properties, now with a Retry filter.
   - Retry only on `NotFoundException` = "no instance in the registry", which the load-balancer
     filter raises BEFORE any request is sent to a server. Response statuses/series are explicitly
     disabled, so a request that already reached a service is not replayed; a register/login that
     already ran cannot be started a second time by this filter.
   - The route also strips the inbound X-Internal-Api-Key header itself: the default-filter that does
     this for the property-defined routes does not apply to a Java-defined route (proven by test:
     without this line a forged header reaches the backend).
   - Backoff budget 5s + 10s + 20s + 20s = up to ~55s of waiting between attempts (4 retries).
     This is a backoff budget, not a guaranteed total: each individual attempt is additionally
     bounded by the cloud profile's 60s httpclient response-timeout. Tunable via
     `grandvista.auth-retry.*` without code changes.
2. `api-gateway/src/main/resources/application.properties` **(modified)**
   - Auth route removed here (now in AuthRouteConfig.java, because retry backoff cannot be expressed
     in the flat properties format). Room/booking/payment routes renumbered 0-2 (list must start at
     0; route ids unchanged, behavior unchanged).
3. `grandvista-frontend/src/pages/LoginPage.jsx` **(modified)**
   - 403 (demo mode disabled on the server) still hides the tab - local mode preserved.
   - 401/429 still show the reviewer access-code panel - reviewer gating untouched.
   - Any other error (503/502/network) now keeps the tab and shows "Demo accounts cannot be reached
     right now - the server may still be waking up" with a Try again button that reloads in place.
4. `grandvista-frontend/src/pages/LoginPage.test.jsx` **(modified)**
   - New test: 503 -> tab stays with retry -> retry succeeds and shows the demo accounts.
   - The demo-disabled test now mocks the real server contract (403 ApiError) instead of a plain Error.
5. `api-gateway/src/test/java/com/grandvista/apigateway/AuthRouteRetryIntegrationTest.java` **(new)**
   - 5 behavioral tests: bridges the no-instance window; still 503 after 5 pre-dispatch attempts when
     nothing wakes; a downstream 503 is not replayed; a POST is dispatched exactly once; the
     X-Internal-Api-Key impersonation header is stripped before reaching the backend.
6. `api-gateway/src/test/java/com/grandvista/apigateway/StubAuthServiceLoadBalancerConfig.java` **(new)**
   - Test-only load-balancer stub for the tests above.
7. `api-gateway/src/test/java/com/grandvista/apigateway/GatewaySecurityIntegrationTest.java` **(modified)**
   - Only adds test properties to shrink the retry backoff in tests (10ms). No assertions changed.

## What this does and does NOT do

- Bridges short wake-up windows automatically (up to ~55s) and gives users a clear retry UI for
  longer ones. It does NOT eliminate free-tier downtime: a wake longer than ~55s still returns 503,
  now with a visible retry instead of a vanished tab.
- No change to reviewer-code security, JWT handling, CORS, payment, booking or room logic.

## Test results (isolated copy, baseline f9ae1ef + these changes)

Total: 329/329 green (backend 214, frontend 115) on baseline f9ae1ef + this patch.
- api-gateway: 20/20 (10 existing security/integration, 5 unit, 5 new behavioral - including
  X-Internal-Api-Key strip and no-post-dispatch-replay proofs).
- frontend: 115/115 (114 existing + 1 new).
- auth 55/55, room 62/62, booking 40/40, payment 33/33, eureka 4/4 (code untouched; rerun for
  cross-module confirmation).

## How to revert

Everything is in one patch: `GV-002-GV-003-fixes.patch` (delivered alongside this file). Revert
applies the patch in reverse - it touches ONLY the files the patch touched, never anything else.

- Revert everything: `git apply -R GV-002-GV-003-fixes.patch` (verified: `git apply -R --check`
  passes against the tree with these changes applied).
- Revert only the gateway change: reverse-apply only the api-gateway hunks
  (`git apply -R --include='api-gateway/*' GV-002-GV-003-fixes.patch`).
- Revert only the frontend change: `git apply -R --include='grandvista-frontend/*' GV-002-GV-003-fixes.patch`.
- Tune the retry without a revert: adjust `grandvista.auth-retry.*` properties (e.g. shorter
  backoffs) in the gateway environment and restart it.
