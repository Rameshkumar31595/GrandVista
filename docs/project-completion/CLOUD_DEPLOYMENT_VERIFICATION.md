# GrandVista — Cloud Deployment Verification Checklist

Date: 2026-10-28. Every line records what was **actually run** and its result.

Legend: **PASS** = executed and passed · **NOT RUN (cloud)** = needs the real Render deployment, which did not exist when
this was written · **N/A** = not applicable.

Where each result was measured:

* **Automated**: Maven/Vitest suites on the development machine.
* **SIM**: local simulation of the Render deployment (`docker/cloud/sim`): the same cloud images
  (`docker/cloud/service.Dockerfile`), the `cloud` Spring profile, the entrypoint, a 512 MB memory limit per
  service, a non-superuser PostgreSQL owner, and throw-away secrets. Differences from Render: plain HTTP between
  containers instead of public HTTPS, a local network instead of the internet, and 0.5 CPU instead of 0.1
  (one 0.1 CPU measurement was taken separately).
* **CLOUD**: the real Render URLs. **No cloud check has been run yet**, because the Render resources don't exist yet
  (see the final report §14). Run section 9 once they do.

Artefacts: `verification/results/cloud_sim_e2e_results.json`, `cloud_sim_ui_results.json`, `screenshots/cloud-sim/`.

## 1. Automated tests (after all changes)

| Suite | Result |
|---|---|
| eureka-server (Maven) | **PASS** 4/4 (3 new: registry Basic auth) |
| api-gateway (Maven) | **PASS** 15/15 (6 new: keep-warm 2, client address 3, CORS access-code header 1) |
| auth-service (Maven) | **PASS** 55/55 (16 new: access guard unit 9, cloud-mode integration 7) |
| room-service (Maven) | **PASS** 62/62 (unchanged tests; `InstanceIdFilter` default label unchanged) |
| booking-service (Maven) | **PASS** 40/40 |
| payment-service (Maven) | **PASS** 33/33 |
| **Backend total** | **PASS 209/209** (was 184 before this work; +25 new) |
| frontend (Vitest) | **PASS** 114/114 (6 new: 5 access-code flow, 1 API client header) |
| frontend production build with a cloud gateway URL | **PASS** (URL baked into the bundle) |
| local Compose files unchanged (`git diff` vs HEAD) and `docker compose config` valid (default, local-review) | **PASS** |
| local-review mode still binds 127.0.0.1 only and never activates the `cloud` profile | **PASS** (`config` output) |

## 2. Infrastructure (SIM)

| Check | Result |
|---|---|
| Cloud images build for all 6 Spring services + frontend | **PASS** |
| All services start with `SPRING_PROFILES_ACTIVE=cloud` and become healthy (cold start of the whole stack 112 s at 0.5 CPU) | **PASS** |
| Memory under the 512 MB limit (idle): gateway 167 MB, eureka 171, auth 235, room 260, booking 242, payment 230 | **PASS** |
| Startup at **0.1 CPU** (Render free CPU), auth-service: **190 s** ("Started … in 189.6 s"), 222 MB | **PASS** (measured; drives the warm-up advice) |
| Each service creates its own logical database on first start (`render-entrypoint: created database grandvista_*_db` ×4), with a non-superuser owner | **PASS** |
| Eureka lists API-GATEWAY, AUTH, ROOM, BOOKING, PAYMENT as UP | **PASS** |
| Eureka password containing `/`, `+`, `=` (3 such characters) works (entrypoint URL-encodes it) | **PASS** |
| Gateway routes to every service through Eureka (`lb://`) | **PASS** |
| Service-to-service (booking → room/payment `/internal/**`) through Eureka | **PASS** (bookings, payments, refunds below) |
| `/internal/**` without key / with wrong key, called directly on room and payment | **PASS** 403 / 403 |
| Load balancing: 2nd Room instance (`lb-demo`), 10 requests → `room-service:room` ×5, `room-service:room2` ×5 | **PASS** |
| Redeploy of auth-service (new container, unique Eureka instance-id): routed again after 74 s; registry has 1 UP instance | **PASS** |
| Keep-warm active in the gateway ("for 5 service(s): every 10 min while users seen in the last 20 min") | **PASS** (log line); actual sleep/wake behaviour: **NOT RUN (cloud)** |
| No critical startup errors after the fixes below | **PASS** |
| Free-tier limits on Render (hours, sleep) | **NOT RUN (cloud)** |

Defects found by the simulation and fixed before the final runs:
1. The gateway would not start: `KeepWarmService` had two constructors and no `@Autowired`.
2. `postgresql-client` was missing from the cloud image, so the database bootstrap was silently skipped.
3. All demo traffic was rate-limited as one client (the gateway's address), so 5 wrong codes from anyone would lock
   out the reviewer. Fixed by adding `ClientAddressFilter`.
4. After a recreate, the old instance's shutdown could mark the new one DOWN because they shared an instance-id. Fixed
   with a unique instance-id per container.

## 3. Web / HTTPS / CORS (SIM)

| Check | Result |
|---|---|
| Frontend `index.html` served; SPA deep link (`/my-bookings`) falls back to index | **PASS** |
| CORS preflight from the frontend origin allows `X-Demo-Access-Code` | **PASS** |
| CORS preflight from a foreign origin → 403 | **PASS** |
| HTTPS (Render-managed TLS) and https-scheme Eureka registration (port 443) | **NOT RUN (cloud)** (SIM uses HTTP by design) |

## 4. Authentication (SIM, through the gateway)

| Check | Result |
|---|---|
| Normal registration → 201; normal login → 200 with a `ROLE_USER` JWT | **PASS** |
| Wrong password → 401 | **PASS** |
| Protected endpoint without a token → 401; tampered JWT signature → 401 | **PASS** |
| Registration with the reserved demo domain → 409 | **PASS** |
| USER → 403 / ADMIN → 200 on `/auth/users`, `/bookings`, `/payments`, `/pricing-rules`, `/rooms/all` | **PASS** (5/5) |
| A USER cannot change roles (403) | **PASS** |
| Token expiry | **PASS** (automated: existing JWT tests; demo tokens use the same issuer/expiry) |

## 5. Demo Login (SIM, through the gateway + real browser)

| Check | Result |
|---|---|
| Account list without the access code → 401 | **PASS** |
| Ramesh / Demo Guest 2 / GrandVista Manager without the code → 401, no token | **PASS** (3/3) |
| Manager with a wrong code → 401, no token | **PASS** |
| With the code: list = Ramesh, Demo Guest 2, GrandVista Manager | **PASS** |
| Ramesh → `ROLE_USER`, Demo Guest 2 → `ROLE_USER`, GrandVista Manager → `ROLE_ADMIN` (JWT decoded) | **PASS** (3/3) |
| 5 wrong codes from one visitor → 6th attempt (even with the right code) 429 + `Retry-After`; other visitors unaffected | **PASS** |
| Demo manager cannot promote a registered user to ADMIN (403), nor demote an administrator (automated) | **PASS** |
| `DEMO_MODE_ENABLED=false` → list 403, login 403 (even with the code); normal registration still 201 | **PASS** |
| `DEMO_ACCESS_EXPIRES_AT` in the past → 403 "Demo access for this review has expired" | **PASS** |
| Demo on + no code (public profile) → auth-service refuses to start ("requires DEMO_REVIEWER_ACCESS_CODE") | **PASS** |
| Browser: Demo tab asks for the code first; wrong code rejected; right code shows 3 cards | **PASS** |
| Browser: Continue as Ramesh → /rooms, Demo Guest 2 → /rooms, GrandVista Manager → /admin | **PASS** (3/3, screenshots) |
| Browser: a new session without the code is asked again; no unexpected console errors | **PASS** |
| Local review mode (no code) unchanged: existing demo tests + Vitest | **PASS** (automated) |

## 6. Business workflows (SIM, synthetic data)

| Check | Result |
|---|---|
| Hotel listing; room search by city | **PASS** |
| Dynamic price quote with nightly breakdown | **PASS** |
| 8 simultaneous bookings of one room/dates by two guests → exactly one 201, seven 409 | **PASS** |
| Ramesh books (PENDING hold) → pays → CONFIRMED; exactly one SUCCESS payment | **PASS** |
| Demo Guest 2 cannot read or cancel Ramesh's booking; sees only own bookings | **PASS** |
| Manager sees the booking in the admin list | **PASS** |
| Cancellation → CANCELLED, payment REFUNDED, room available again | **PASS** |
| Simulated payment failure → not confirmed, payment stored as FAILED | **PASS** |
| Manager creates a hotel (201); Ramesh cannot (403); inventory summary | **PASS** |
| Bookings persist and are listed again | **PASS** |
| Saga recovery under a payment-service outage | **PASS** in the earlier local verification (`live_saga.py`, unchanged code); **not re-run** in SIM/CLOUD |

## 7. PostgreSQL (SIM)

| Check | Result |
|---|---|
| Four logical databases in one instance, created on first start | **PASS** |
| `create-service-logins.sql` with a non-superuser `CREATEROLE` owner → exit 0 | **PASS** |
| `move-tables-to-service-login.sql` → all 4 room tables owned by `gv_room` | **PASS** |
| `gv_room` → own database connects and reads 16 rooms; auth/payment databases → "permission denied" | **PASS** |
| Services keep working after the ownership move (rooms via gateway 200) | **PASS** |
| Render owner login has `CREATEROLE`; TLS to Render Postgres; 30-day expiry | **NOT RUN (cloud)** |

## 8. Safety / regression

| Check | Result |
|---|---|
| Windows PostgreSQL (5433) never connected to or modified | **PASS** (no command targeted it) |
| Docker volume `grandvista_pgdata` and the local images/containers untouched; simulation has no volumes (tmpfs) and its own project | **PASS** |
| `.env`, `grandvista-local.properties` not read for or modified by this work | **PASS** |
| No simulation secret value found in any repository file | **PASS** (value scan) |
| Existing heuristic secret scan: no hits in new cloud files (28 pre-existing heuristic hits, all variable names/test fixtures) | **PASS** |

## 9. Cloud checks to run after the Render deployment

```bash
cd docs/project-completion/verification
GV_GATEWAY=https://<gateway>.onrender.com GV_DEMO_CODE='<reviewer code>' \
GV_EUREKA=https://<eureka>.onrender.com GV_EUREKA_PASSWORD='<from env group>' GV_FRONTEND=https://<web>.onrender.com \
GV_SERVICE_URLS=https://<room>.onrender.com,https://<payment>.onrender.com \
GV_RESULTS=results/cloud_render_e2e_results.json python cloud/cloud_e2e.py
GV_FRONTEND=https://<web>.onrender.com GV_DEMO_CODE='<reviewer code>' GV_SHOTS_DIR=results/screenshots/cloud-render node cloud/cloud_ui_demo.mjs
```

The first run waits up to 10 minutes for sleeping services. Use `GV_TEST_RATE_LIMIT=0` just before the review so the
wrong-code test doesn't use up part of the global limit.
