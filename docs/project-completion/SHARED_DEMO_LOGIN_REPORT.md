# GrandVista — Demo Login in Shared Database Mode (LOCAL_REVIEW)

Date: 2026-10-27. **Status: implemented and runtime-verified; currently ON.** Nothing committed or pushed. No secret in this report.

## 1. Why Demo Login had disappeared

`docker-compose.shared-db.yml` deliberately forces `DEMO_MODE_ENABLED=false` (so the real databases never receive demo rows by accident). That is still the case.
Demo Login is now an **explicit, separate opt-in**: a third Compose file, `docker-compose.local-review.yml`.

## 2. How localhost-only is enforced (checked before enabling)

Finding before the change: the published ports were bound to **all interfaces** (`0.0.0.0:8080`, `:5173`, `:9000`) and the gateway answered on the machine's
LAN addresses (192.168.56.1 / 192.168.1.219 returned HTTP 200). So CORS or "it is only my laptop" was **not** a protection; an unchanged config must not expose a passwordless admin.

Enforcement chosen — the Docker port bindings themselves:

* `docker-compose.local-review.yml` replaces (`ports: !override`) the three published ports with `127.0.0.1:8080:8080` (gateway), `127.0.0.1:5173:8080` (UI), `127.0.0.1:9000:9000` (Eureka).
  The kernel then accepts those connections only on the loopback interface; other machines cannot open a TCP connection at all.
* Auth Service (which owns the demo endpoint) is **not published**; it is reachable only through the gateway (`/api/auth/demo/*`) or from containers on the private compose network. The isolated Postgres container is also unpublished.
* This is not app-level IP filtering on purpose: behind Docker Desktop every connection appears to containers as coming from the bridge gateway, so a "remote address is localhost" check inside the app would be meaningless.

Verified at runtime (`docs/project-completion/verification/local-review-check.sh`): `docker ps` shows only `127.0.0.1:` bindings; `127.0.0.1:8080` → 200; **192.168.56.1, 192.168.1.219 and 172.20.240.1 on ports 8080/5173/9000 → connection refused**. The script exits non-zero if any port is bound to all interfaces, so the guarantee can be re-checked at any time. Because it held, the demo was enabled.

## 3. What changed

| File | Change |
|---|---|
| `docker-compose.local-review.yml` (new) | `DEMO_MODE_ENABLED=true` for auth-service; loopback-only port bindings. Documents on/off commands. |
| `auth-service/.../DemoAccountInitializer.java` | Collision hardening: an existing row at a demo e-mail whose **role differs** is left completely untouched (warn only; demo login for it stays refused). Existing demo rows are never given a new password. |
| `auth-service/.../DemoAccountInitializerTest.java` (new) | 6 unit tests (below). |
| `docs/.../verification/local-review-check.sh` (new) | Loopback-only verification script. |

Unchanged on purpose: the demo implementation itself, normal login, JWT issuing, gateway rules, `docker-compose.yml`, `docker-compose.shared-db.yml` (still forces demo OFF on its own), application default `DEMO_MODE_ENABLED=false`.
Demo accounts (fixed allowlist, dedicated reserved-domain e-mails): Ramesh `ROLE_USER`, Demo Guest 2 `ROLE_USER`, GrandVista Manager `ROLE_ADMIN`; random unknown passwords, so the password login cannot use them; public registration cannot claim the reserved domain.

## 4. Commands

```bash
# Demo ON  (shared DB + review mode)
docker compose -f docker-compose.yml -f docker-compose.shared-db.yml -f docker-compose.local-review.yml up -d --build
bash docs/project-completion/verification/local-review-check.sh          # must print "loopback-only confirmed"

# Demo OFF (shared DB, original behaviour; ports return to the base 0.0.0.0 bindings on a full `up -d`)
docker compose -f docker-compose.yml -f docker-compose.shared-db.yml up -d

# Back to isolated mode
docker compose -f docker-compose.yml -f docker-compose.shared-db.yml down && docker compose up -d
```
Turning demo off leaves the three demo rows in `grandvista_auth_db`; they are unusable (endpoints answer 403, random passwords). They are not deleted automatically.

## 5. Tests

auth-service: **39 tests, 0 failures** (6 new in `DemoAccountInitializerTest`, plus the 15 demo tests from before, all re-run):

| Requirement | Test |
|---|---|
| Disabled mode | `disabledModeNeverTouchesTheUserTable`; `DemoLoginDisabledIntegrationTest` (403 on both endpoints, no demo rows, no token) |
| Enabled mode | `enabledModeCreatesOnlyTheThreeMissingDemoAccounts…`; `DemoLoginEnabledIntegrationTest` (list, guest/admin login, JWT roles) |
| Role isolation | guest token → 403 on `/auth/users`, admin → 200; role change of a demo row → demo login refused |
| Account collisions | different-role collision untouched; existing demo row keeps its password; a real user named "Ramesh" is not touched; reserved-domain registration → 409 |
| Normal-login compatibility | `normalLoginAndRegistrationStillWork`; demo accounts cannot password-login |
| Prod guard | enabled + `prod` profile → refuses to start |

## 6. Runtime verification (Docker, shared Windows PostgreSQL)

* Safety first: fresh validated backups of all four databases (timestamp in `GrandVista-DB-Backups/`, 4 files) and a before-snapshot of counts + row hashes.
* Containers: gateway, auth, room, booking, payment, Eureka healthy; Eureka lists API-GATEWAY, AUTH-SERVICE, ROOM-SERVICE, BOOKING-SERVICE, PAYMENT-SERVICE as UP.
* **Shared DB still connected:** inside the containers `DB_HOST=host.docker.internal`, `DB_PORT=5433`, room `SEED_DEMO_DATA=false`; data served through the gateway comes from the Windows databases (below).
* **Database effect:** auth users 12 → **15** (exactly the three new demo rows, ids 13–15). An MD5 over the 12 original user rows is **identical** before/after (`c995216d…`); hotels 8, rooms 27, pricing rules 8, holds 18, bookings 18, payments 18 — all unchanged, nothing deleted or reset, no migrations run.
* Demo login: JWTs decoded — Ramesh `[ROLE_USER]`, Demo Guest 2 `[ROLE_USER]`, GrandVista Manager `[ROLE_ADMIN]`; unknown id → 401; password login for a demo e-mail → 401; reserved-domain registration → 409.
* Role restrictions through the gateway: Ramesh → 403 on users, bookings, payments, pricing-rules, rooms/all; his own `bookings/my` and `payments/my` are empty (separate from existing users). Manager (read-only GETs) sees 15 users, 18 bookings, 18 payments, 27 rooms, 8 hotels.
* Toggle test: with the review file omitted, `/api/auth/demo/*` → 403; with it, 200.
* No bookings or payments were created (no state-changing flows were run against the shared databases).

## 7. Limitations and risks

* While ON, anyone using **this machine** (or a browser on it) can become the demo administrator, who can change user roles, including promoting real users. Use only for the demonstration and turn it off afterwards.
* The guarantee depends on the port bindings. Running a plain `docker compose … up -d` **without** the review file rebinds ports to all interfaces (original behaviour) *and* turns demo off — that combination is safe. Do not hand-edit bindings back to `0.0.0.0` while demo is on; re-run the check script.
* Other software on the same PC that forwards localhost (tunnels, port-forwarding tools, remote-desktop browsers) would bypass the guarantee.
* Anything the demo guests do (bookings, payments) is written to the **real** shared databases, by design of shared mode.
* The isolated `postgres` container was found running again (Docker restart policy); it is unused in shared mode and harmless.
* Not verified: the existing real users' passwords/login (not available); normal login for the 12 original users is protected by their unchanged rows (hash identical) and by the normal-login tests.
