# GrandVista — Demo Login Implementation Report

Date: 2026-10-27. Nothing was committed or pushed.

## 1. Summary

The login page gains an optional **Demo Login** tab with three fixed account cards. It is **off by default**; the server
decides whether it exists. Normal e-mail/password login is unchanged. No real user, password or the real admin account is
involved, and the Windows PostgreSQL (port 5433) was never touched.

## 2. Authorization model found

Only two roles exist: `ROLE_USER` (guest) and `ROLE_ADMIN`. No `HOTEL_OWNER` was introduced.

## 3. Demo accounts

| Card (display name configurable) | Fixed id | Role | E-mail (reserved domain) |
|---|---|---|---|
| Ramesh | `ramesh` | ROLE_USER | demo.ramesh@demo.grandvista.local |
| Demo Guest 2 | `guest2` | ROLE_USER | demo.guest2@demo.grandvista.local |
| GrandVista Manager | `manager` | ROLE_ADMIN | demo.manager@demo.grandvista.local |

Display names: `DEMO_GUEST1_NAME`, `DEMO_GUEST2_NAME`, `DEMO_MANAGER_NAME`.

## 4. Authentication approach

* `GET /auth/demo/accounts` and `POST /auth/demo/login {accountId}` in auth-service (gateway: `/api/auth/demo/...`).
* Both answer **403 "Demo login is disabled on this server"** unless `grandvista.demo.enabled` (env `DEMO_MODE_ENABLED`, default `false`).
* The id must match a fixed enum allowlist (`DemoAccount`); anything else → 401. The role comes from the enum, never from the request.
  The login also requires the stored row to still have that role (if an admin re-roled a demo account, login is refused).
* The token is produced by the existing `JwtTokenProvider` (same issuer, expiry, `roles` claim); downstream services and the gateway are unchanged.
* Demo rows are created at startup (only when enabled, idempotent, never overwrite existing rows, only the demo account's display name is synced).
  Each gets a random BCrypt password that is never stored or logged → the accounts **cannot** use the password login.
* Public registration refuses the reserved `@demo.grandvista.local` domain (409), so a real user can never occupy a demo identity.
* Startup fails if demo mode is enabled together with the `prod`/`production` Spring profile.
* Frontend holds no passwords/tokens: it asks the server for the account list; if that returns 403/error, the Demo tab is hidden.

## 5. Demonstration data

The existing seeder (4 hotels, 16 rooms, 4 pricing rules, only when the room DB is empty) already supports the workflows; no new seed data or
duplication was needed. Bookings and users are never reset or deleted.

## 6. Enable / disable

Compose (opt-in, no image rebuild needed for toggling — only auth-service reads it):

```bash
# enable for one run (shell variable; .env untouched)
DEMO_MODE_ENABLED=true docker compose up -d auth-service        # PowerShell: $env:DEMO_MODE_ENABLED="true"; docker compose up -d auth-service
# or set DEMO_MODE_ENABLED=true in your local .env (git-ignored) and run: docker compose up -d auth-service
# disable
docker compose up -d auth-service        # with the variable unset / false
```

First deployment of this change needs `docker compose build auth-service api-gateway frontend` (new UI, controller, gateway rule), then `up -d`.
Native run: set `DEMO_MODE_ENABLED=true` in `grandvista-local.properties` or the environment. Disabling later leaves the three demo rows in the
database but they become unusable (endpoints return 403 and passwords are random).

## 7. Files changed

Backend: `auth-service/.../demo/` (new: DemoAccount, DemoProperties, DemoLoginService, DemoAccountInitializer, DemoAuthController, DemoLoginRequest,
DemoAccountResponse, DemoModeDisabledException), `SecurityConfig`, `GlobalExceptionHandler`, `AuthServiceImpl` (reserved domain), `User` (`setName`),
`application.properties`; gateway `GatewaySecurityConfig`.
Frontend: `LoginPage.jsx`, `AuthContext.jsx`, `api/endpoints.js`, `api/client.js`, `styles/app.css`.
Deployment: `docker-compose.yml`, `.env.example`.
Tests (new/extended): `DemoLoginEnabledIntegrationTest`, `DemoLoginDisabledIntegrationTest`, `GatewaySecurityIntegrationTest`, `LoginPage.test.jsx`, `AuthContext.test.jsx`.

## 8. Test results

| Suite | Result |
|---|---|
| auth-service (Maven) | 33 tests, 0 failures (15 new: enabled 12, disabled 3) |
| api-gateway (Maven) | 9 tests, 0 failures (1 new) |
| frontend (Vitest) | 108 tests, 0 failures (was 100; +8) |
| frontend production build | succeeded |
| Docker live checks (gateway :8080, Docker PostgreSQL) | see below |

Requirement coverage: (1) normal login/registration — enabled-suite `normalLoginAndRegistrationStillWork`; (2)(3)(4) guest/admin demo login with JWT roles decoded;
(5) unknown ids rejected; (6) disabled → 403 + no demo rows created; (7) guest token gets 403 on `/auth/users`, admin 200; (8) existing auth, gateway and
frontend suites still pass (booking/room/payment services unchanged and not re-run); (9) gateway test + live run; (10) LoginPage tests.

Live Docker run (rebuilt auth-service, api-gateway, frontend; `pgdata` volume kept):
* Default config: `/api/auth/demo/accounts` and `/login` → 403; 0 demo rows.
* `DEMO_MODE_ENABLED=true`: accounts listed; JWT roles `ROLE_USER`/`ROLE_ADMIN`; unknown id → 401; guest got 403 on bookings list, pricing rules, users, rooms/all; manager got 200.
* Ramesh created a booking and paid (UPI); Demo Guest 2 got 403 on that booking and an empty "my bookings"; manager saw it; manager cannot create bookings (USER-only rule).
* Restart with demo on: still exactly 4 users (admin + 3 demo), no duplicates. Password login for the demo e-mail → 401; registering a demo-domain e-mail → 409.
* Browser: Demo tab shows three cards; "Continue as GrandVista Manager" opened the admin dashboard.
* Afterwards auth-service was recreated with the default (demo off): endpoint 403 again.

Test artefacts left in the Docker DB: one booking by Ramesh (confirmed, room 9) and the three demo accounts.

## 9. Security limitations

* While enabled, anyone who can reach the gateway can sign in as the demo **administrator** — only enable on a trusted local machine.
  The demo admin can change other users' roles (including promoting real users) and manage all hotels/rooms/pricing, since it is a real `ROLE_ADMIN`.
* No rate limiting on the demo endpoint; tokens last the normal expiry (60 min default) and are not revocable.
* The demo tab visibility is a UI hint only; enforcement is server-side.
* The prod-profile guard only works if production actually sets a `prod`/`production` profile; default is off in any case.
* Display names are cosmetic; the e-mails and ids are fixed.

## 10. Unresolved / notes

* Backend tests for booking/room/payment services were not re-run (untouched).
* The demo admin can demote itself's peers (e.g., the real admin) — documented above, not restricted, to keep "manage users" in the demo.
* An unauthenticated visitor with demo disabled causes one expected 403 request in the browser console on the login page.
* Reviewer may want `DEMO_MODE_ENABLED=true` set in their own `.env`; the committed `.env.example` keeps it `false`.
