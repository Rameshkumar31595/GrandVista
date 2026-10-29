# GrandVista — Cloud Deployment Final Report

Date: 2026-10-28 · Target: Render (free tier) with Docker · Companion documents: `CLOUD_DEPLOYMENT_USER_GUIDE.md`,
`CLOUD_DEPLOYMENT_VERIFICATION.md`.

## Final status: **PARTIAL**

The cloud deployment is fully implemented. It was verified end-to-end in a local simulation of the Render setup:
51/51 API checks, 8/8 real-browser checks, all operational scenarios, and every automated suite (209 backend +
114 frontend). **It is not live on Render yet.** Creating the Render resources requires the project owner's Render
account. Per the owner's decision, the owner creates the Blueprint (User Guide §1). No cloud URL exists, so no cloud
runtime check has been run, and none is claimed.

---

## 1. Architecture

```
Browser ──HTTPS──► grandvista-web (Render static site / CDN: React build)
   │
   └─HTTPS──► grandvista-gateway (Spring Cloud Gateway, JWT edge security, CORS, keep-warm)
                 │  lb://SERVICE via Eureka  → https://<service>.onrender.com:443
                 ├──► grandvista-auth     ─┐
                 ├──► grandvista-room      ├─ each: own JWT validation + roles; /internal/** needs the internal key
                 ├──► grandvista-booking ──┤  booking → room/payment over HTTPS via Eureka (Saga, recovery job)
                 └──► grandvista-payment  ─┘
              grandvista-eureka (registry, HTTP Basic protected)  ◄── all six register their public HTTPS name
              grandvista-db (Render PostgreSQL, free): grandvista_auth_db | _room_db | _booking_db | _payment_db
              (each service connects over Render's internal network)
```

Services stay separate (6 Docker web services + 1 static site + 1 database), with the same Eureka, gateway, JWT,
Saga and per-service database ownership as locally.

**Forced deviations from the local architecture** (all from verified Render rules):

| Local | Cloud | Reason |
|---|---|---|
| Services talk over the private Compose network; only gateway/UI/Eureka published | Every service has a public HTTPS URL; services reach each other through it | Render: "Free web services can send private network requests, but they can't receive them" |
| `/internal/**` unreachable from outside | `/internal/**` reachable but needs `X-Internal-Api-Key` (unchanged mechanism; verified 403 without/with a wrong key) | follows from the row above |
| Eureka dashboard open on localhost | Eureka requires HTTP Basic credentials (health stays open) | a public registry would let anyone register a fake ROOM-SERVICE |
| Instances registered by IP:port | Registered by public host name, `https`, port 443, unique instance-id per container | public routing, zero-downtime redeploys |
| Ports 8080–8084/9000 | Each container listens on Render's `PORT` (10000) | Render convention |
| Separate PostgreSQL databases on one server | Four logical databases in **one** free instance | one free Postgres per workspace |
| Always on | Free services sleep after 15 idle min; gateway keep-warm only while users are active | Render free tier |
| Passwordless Demo Login (localhost only) | Demo Login behind a reviewer access code | public URL (§8) |

## 2. Selected provider: Render

## 3. Why Render

* It's the user's preferred platform and the one the professor demonstrated. It supports Docker web services from a
  GitHub repository, a free static site, free PostgreSQL, and a Blueprint (`render.yaml`) for the whole multi-service
  setup.
* Every required component fits on free instance types without merging services. Alternatives were considered but
  not needed. Most "free" container hosts that can run six JVMs require a payment card for verification, or are trials
  or credits, which the ₹0/no-billing rule excludes.

## 4. Free-tier compatibility and limitations (Render docs, read 2026-10-28)

| Fact | Effect on GrandVista |
|---|---|
| Free web service: 512 MB RAM, 0.1 CPU | Measured idle memory 167–260 MB per service (512 MB limit), so it fits. Startup at 0.1 CPU is ~190 s for auth-service. |
| Sleeps after 15 min without inbound traffic; ~1 min to wake | Cold start of the system is several minutes. Warm up 5–10 min before the review. Gateway keep-warm (§7). |
| 750 free instance hours per workspace per month; sleeping services use none; all free services suspended when used up | 6 Java services awake = 6 h per hour. About 125 h of fully-awake use per month. Keep-warm stops when users leave. |
| Free web services can't receive private network traffic | Public HTTPS between services (§1) |
| One free PostgreSQL per workspace, 1 GB, **expires 30 days after creation** (+14-day grace), no backups | One instance with 4 logical databases. Plan the review within 30 days. `pg_dump` for backups. |
| Ephemeral filesystem, single instance per free service | Nothing is stored on disk. The load-balancing demo uses a second Room service (optional block in `render.yaml`). |
| Private GitHub repos supported via the Render GitHub app | Not needed: the repository is already public (§14) |
| A payment method is only relevant for paid usage; without one, free services are suspended at limits | Keep no card on file (User Guide §14) |

## 5. PostgreSQL configuration

* `render.yaml` creates `grandvista-db` (free, Singapore, default database `grandvista_cloud`, owner `grandvista_owner`).
* Each database service gets `DB_HOST/PORT/USERNAME/PASSWORD/ADMIN_DATABASE` from `fromDatabase`, plus
  `DB_BOOTSTRAP_DATABASE` (its own database). On first start, `docker/cloud/render-entrypoint.sh` runs
  `CREATE DATABASE` if the database is missing. It is idempotent and never drops or alters anything.
* JDBC URL (cloud profile): `jdbc:postgresql://${DB_HOST}:${DB_PORT}/<db>?sslmode=${DB_SSLMODE:prefer}`. Internal
  Render connection; set `DB_SSLMODE=require` to force TLS. External connections (pgAdmin) are always TLS on Render.
* Small pools: Hikari max 3, min idle 1 per service.
* Optional isolation: `docker/cloud/create-service-logins.sql` + `move-tables-to-service-login.sql` create
  `gv_auth/gv_room/gv_booking/gv_payment`, each able to connect only to its own database. Verified with a
  non-superuser `CREATEROLE` owner. Whether Render's owner login has `CREATEROLE` is **unverified**.
* Fresh synthetic data only: the room service seeds 4 hotels / 16 rooms / pricing rules into an empty database.
  Nothing from the Windows databases is copied, and no local record was read or changed.
* Backups, restore and expiry: User Guide §8 and §12.

## 6. Docker configuration

* `docker/cloud/service.Dockerfile`: same two-stage build as `docker/service.Dockerfile` (Maven + Temurin 25 →
  Temurin 25 JRE, non-root user). It adds `postgresql-client` and the entrypoint script. The local Dockerfile is
  unchanged. Render passes `SERVICE_DIR` (an env var) as the Docker build argument.
* `docker/cloud/render-entrypoint.sh` does four things:
  * activates profile `cloud`
  * builds `EUREKA_URL` with URL-encoded Basic credentials (works with generated Base64 passwords containing `/+=`)
  * builds the gateway's keep-warm URL list
  * runs the database bootstrap and sets JVM options for 512 MB (SerialGC, MaxRAMPercentage 60, C1 only, smaller
    stacks and code cache)
* `application-cloud.properties` in each service is loaded only when `SPRING_PROFILES_ACTIVE=cloud`. STS, `mvn` and
  all local Compose files never activate it.
* Frontend: Render static site. `npm ci && vite build`, with `VITE_API_BASE_URL` taken from the gateway's
  `RENDER_EXTERNAL_URL`, an SPA rewrite, and the same security headers as the local nginx.
* Health checks: `/actuator/health` on every service.

## 7. Eureka and API Gateway networking

* Clients register `hostname = RENDER_EXTERNAL_HOSTNAME`, secure port 443, non-secure port disabled, and
  https home/status/health URLs. Spring Cloud LoadBalancer therefore resolves `lb://ROOM-SERVICE` to
  `https://<room>.onrender.com`, both in the gateway and in booking-service's RestClients.
* Instance-id `<app>:<host>:${RENDER_INSTANCE_ID}`. The simulation showed that a shared id lets the old instance's
  shutdown mark the new one DOWN.
* LoadBalancer cache TTL 10 s (gateway, booking), so woken or redeployed instances are used sooner.
* Gateway timeouts in the cloud: connect 10 s, response 60 s. Booking's downstream calls: connect 10 s, read 20 s
  (payment recovery delay 60 s stays above it).
* `KeepWarmService` (gateway, cloud only): when the gateway starts, and then every 10 min while a real `/api/**`
  request was seen in the last 20 min, it pings the health URLs of Eureka and the four services. It does not keep
  anything awake when nobody uses the app.
* `ClientAddressFilter` (gateway, cloud only): sends the visitor's address (`X-GrandVista-Client`) for the demo rate
  limit. It takes the last `X-Forwarded-For` hop appended by a trusted private-range proxy and overwrites any
  client-sent value.
* **Not yet verified on Render:** Host-header/SNI behaviour of HTTPS service-to-service calls through Render's edge.
  It is expected to work (standard HTTPS to a public host), but the simulation uses HTTP.

## 8. Demo Login implementation (cloud)

* The local Review Mode is unchanged. Without `DEMO_REVIEWER_ACCESS_CODE`, behaviour is exactly as before
  (all existing demo tests pass).
* New `DemoAccessGuard` (auth-service):
  * order of checks: demo off → 403; access window expired (`DEMO_ACCESS_EXPIRES_AT`) → 403; too many wrong codes
    (5 per visitor, 30 overall per 15 min) → 429 + `Retry-After`; code missing or wrong → 401
  * the code is stored only as a SHA-256 digest, compared in constant time, and never logged
  * a missing code doesn't count as a failure
* The cloud profile sets `required=true`: demo mode on without a code (or with one under 12 characters) **refuses to
  start**. Verified.
* `restrict-demo-admin=true` (cloud): the shared GrandVista Manager session cannot grant ROLE_ADMIN, and cannot
  change an administrator's role. This stops privilege escalation through the shared demo account. The manager
  keeps the hotel/room/pricing/booking/payment administration.
* Same three fixed identities and roles (Ramesh USER, Demo Guest 2 USER, GrandVista Manager ADMIN), same JWT issuer,
  expiry and signature, same gateway and service role checks.
* UI: the Demo Login tab asks for the reviewer access code, then shows the same three "Continue as …" cards. The code
  is kept only in that tab's sessionStorage and is never in the build. If the server revokes the code, the UI asks
  for it again.
* Enable/disable: `DEMO_MODE_ENABLED`, rotate `DEMO_REVIEWER_ACCESS_CODE`, optional `DEMO_ACCESS_EXPIRES_AT`.
  Dashboard env vars, no rebuild.

## 9. Security measures (summary)

* Secrets come only from Render (generated `JWT_SECRET`, `INTERNAL_API_KEY`, `EUREKA_PASSWORD` in the
  `grandvista-shared` env group) or are typed in once (`ADMIN_PASSWORD`, `DEMO_REVIEWER_ACCESS_CODE`).
  None are in the repository; verified with a value scan.
* Eureka is Basic-auth protected. Anonymous registry reads and fake registration return 401 (verified).
* JWT is validated at the gateway and in every service. `/internal/**` needs the internal key. The gateway strips any
  client-sent `X-Internal-Api-Key`.
* CORS allows only the frontend origin, and now also the `X-Demo-Access-Code` header.
* Swagger/API docs are off on the public service URLs (`API_DOCS_ENABLED=true` turns them on).
* No real payment provider. The simulated gateway is unchanged.
* Remaining risks: see §14.

## 10. Files added and modified

**Added**

* `render.yaml`: Render Blueprint (free plans only)
* `docker/cloud/service.Dockerfile`, `docker/cloud/render-entrypoint.sh`
* `docker/cloud/create-service-logins.sql`, `docker/cloud/move-tables-to-service-login.sql`
* `docker/cloud/sim/docker-compose.cloud-sim.yml`, `run-cloud-sim.sh`, `init-owner.sh`: local Render simulation
* `*/src/main/resources/application-cloud.properties` ×6
* eureka-server: `security/RegistryBasicAuthFilter.java`, `security/RegistrySecurityConfig.java`;
  test `RegistryBasicAuthIntegrationTest.java`
* api-gateway: `cloud/KeepWarmProperties.java`, `cloud/KeepWarmService.java`, `cloud/ClientAddressFilter.java`;
  tests `cloud/KeepWarmServiceTest.java`, `cloud/ClientAddressFilterTest.java`
* auth-service: `demo/DemoAccessGuard.java`, `DemoAccessProperties.java`, `DemoAccessDeniedException.java`,
  `DemoAccessRateLimitedException.java`; tests `DemoAccessGuardTest.java`, `DemoLoginCloudModeIntegrationTest.java`
* `docs/project-completion/CLOUD_DEPLOYMENT_FINAL_REPORT.md`, `CLOUD_DEPLOYMENT_USER_GUIDE.md`,
  `CLOUD_DEPLOYMENT_VERIFICATION.md`
* `docs/project-completion/verification/cloud/cloud_e2e.py`, `cloud_ui_demo.mjs`
* results: `cloud_sim_e2e_results.json`, `cloud_sim_ui_results.json`, `screenshots/cloud-sim/*.png`

**Modified** (all backwards compatible; defaults keep local behaviour)

* auth-service:
  * `DemoAuthController`: access-code header and client address
  * `DemoModeDisabledException`: message constructor
  * `GlobalExceptionHandler`: 401/429 handlers
  * `UserManagementServiceImpl`: demo-admin restriction, off unless configured
  * `application.properties`: two new keys, empty by default
* api-gateway: `GatewaySecurityConfig` (CORS allows `X-Demo-Access-Code`); `GatewaySecurityIntegrationTest` (+1)
* room-service: `InstanceIdFilter` (optional `grandvista.instance-label`; default `room-service:<port>` unchanged)
* frontend:
  * `api/client.js`: optional extra headers
  * `api/endpoints.js`: optional access code
  * `context/AuthContext.jsx`
  * `pages/LoginPage.jsx`: access-code step
  * tests `LoginPage.test.jsx` (+5), `client.test.js` (+1)

**Unchanged:** `docker-compose.yml`, `docker-compose.shared-db.yml`, `docker-compose.local-review.yml`,
`docker/service.Dockerfile`, `docker/frontend.Dockerfile`, `docker/nginx.conf`, `.env.example`, `.env`,
`grandvista-local.properties`, all booking/payment code.

## 11. Tests executed and results

* Automated, after all changes: backend **209/209** (eureka 4, gateway 15, auth 55, room 62, booking 40,
  payment 33; 25 new). Frontend Vitest **114/114** (6 new). Production build OK.
* Simulation: API end-to-end **51/51**, browser Demo Login journey **8/8**. Operational checks all passed:
  redeploy, disable, expiry, fail-closed, load balancing 5/5, 0.1 CPU startup, database isolation.
* The simulation found 4 defects, fixed before the final run (Verification §2).
* Cloud: **not run** (no deployment yet).

Details: `CLOUD_DEPLOYMENT_VERIFICATION.md`.

## 12. Public deployment URL

None yet. After the Blueprint is applied, the URLs are `https://grandvista-web.onrender.com` (UI) and
`https://grandvista-gateway.onrender.com` (API), with a suffix if Render assigns one.

## 13. Deployment status per component

| Component | Implemented | Simulation | Render |
|---|---|---|---|
| React frontend (static site) | Yes | Running, verified | Not created |
| Eureka (protected) | Yes | Running, verified | Not created |
| API Gateway | Yes | Running, verified | Not created |
| Auth (incl. cloud Demo Login) | Yes | Running, verified | Not created |
| Room (+ optional 2nd instance) | Yes | Running, verified (LB 5/5) | Not created |
| Booking (Saga) | Yes | Running, verified | Not created |
| Payment (simulated) | Yes | Running, verified | Not created |
| PostgreSQL (4 logical DBs) | Yes | Running, verified | Not created |

## 14. Unresolved blockers and risks

1. **Render resources not created.** The owner applies the Blueprint (User Guide §1), types in `ADMIN_PASSWORD`
   and `DEMO_REVIEWER_ACCESS_CODE`, then runs the cloud checks (Verification §9).
2. **The GitHub repository is PUBLIC** (GitHub API: `"visibility": "public"`). It was already public before this
   work. The rules said not to *make* it public, and visibility was not changed.

   It tracks `GrandVista-DB-Backups/*.backup`: 12 `pg_dump` files of the local databases, committed on 2026-10-27,
   which are likely to contain user names, e-mails, BCrypt password hashes, bookings and payments.

   **Recommendation (owner's decision):** make the repository private (Render can still deploy it via its GitHub
   app), or remove the backups from the repository and its history. Nothing was deleted or changed here.
3. `fromService … envVarKey: RENDER_EXTERNAL_URL` between services is documented by Render for self-references. The
   cross-service form is used in community examples but has not been verified here. User Guide §1.7 describes the
   manual fallback.
4. Not yet verified on Render:
   * HTTPS service-to-service calls through Render's edge
   * Render owner `CREATEROLE` (optional isolation only)
   * real sleep/wake timings
5. Cold starts: a fully asleep system needs several minutes (auth alone ~190 s at 0.1 CPU). During wake-up the
   gateway may answer 503 until services re-register.
6. The demo rate-limit counters are in memory (reset on restart). A visitor calling auth-service directly can forge
   the client-address headers. The global limit (30 per 15 min) still caps guessing. A deliberate attacker who
   knows the URL can trigger a temporary 15-min lockout. In that case, rotate the code.
7. The saga outage scenarios (`live_saga.py`) were not re-run against the cloud setup (code unchanged).

## 15. Cost and expiry

* **₹0.** All resources are on `plan: free` or are free static sites. No paid feature is used: no disks,
  autoscaling, private services, workers or pre-deploy commands. Nothing was purchased and no billing was touched.
* PostgreSQL expires 30 days after creation (then a 14-day grace period). 750 free instance hours per month per
  workspace. When limits are reached, free services are suspended; nothing is charged as long as no payment method
  or paid plan is added.

## 16. Git status

The cloud changes are committed on a **new branch `cloud-deployment`** and pushed, with the owner's approval
(2026-10-28). `main` is unchanged. Nothing else was committed or pushed.

## 17. Final status: **PARTIAL**

Ready to deploy and verified in simulation. The cloud deployment itself is pending the owner's Render Blueprint step.
