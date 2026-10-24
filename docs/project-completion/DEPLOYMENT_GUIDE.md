# GrandVista – Deployment Guide

> **What has actually been executed.** The *native* start-up (Maven jars + PostgreSQL + Vite) was run end to end on the
> development machine against a disposable PostgreSQL instance (see TEST_VERIFICATION_REPORT.md). The *Docker* files were
> written and **statically validated only** – Docker is not installed on the machine where this work was done, so no
> image was built and no container was started. The first `docker compose up --build` on your side is the first real
> execution; section 4 lists what to look at if it misbehaves.

## 1. Prerequisites

| Tool | Version used | Needed for |
|---|---|---|
| JDK | 25 (Temurin / any JDK 25) | native run, `mvn test` |
| Maven | 3.9.x | native run, `mvn test` |
| Node.js / npm | Node 20.19+ (tested 24.13) | frontend, `npm test` |
| PostgreSQL | 18 on port **5433** (any 14+ works) | native run |
| Docker Desktop / Engine with Compose v2 | any recent | container run (optional) |

Ports used: Eureka 9000 · gateway 8080 · auth 8081 · room 8082 (+8085 for the second instance) · booking 8083 ·
payment 8084 · frontend 5173 · PostgreSQL 5433.

## 2. Configuration

### 2.1 Native development – `grandvista-local.properties`

Copy `grandvista-local.properties.example` to `grandvista-local.properties` in the repository root (git-ignored).

| Key | Required | Meaning |
|---|---|---|
| `DB_HOST`, `DB_PORT` | – | default `localhost`, `5433` |
| `DB_USERNAME`, `DB_PASSWORD` | yes | PostgreSQL login |
| `JWT_SECRET` | yes | Base64, ≥ 32 bytes (`openssl rand -base64 48`) |
| `INTERNAL_API_KEY` | yes | long random string shared by booking / room / payment |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | for admin login | demo administrator created at first start (password ≥ 8 chars) |
| `CORS_ALLOWED_ORIGINS` | – | default `http://localhost:5173` |
| `HOLD_MINUTES` | – | payment window / room hold, default 15 |

Operating-system environment variables **override** the file. A Windows *user-level* `DB_PASSWORD` left over from another
project is the most common reason for "password authentication failed" (README §6).

Optional tuning of the recovery job (defaults in `booking-service/src/main/resources/application.properties`):
`grandvista.booking.payment-recovery-after=60s` (must stay well above the 10 s client read timeout) and
`grandvista.booking.cancellation-retry-after=30s`.

### 2.2 Docker – `.env`

Copy `.env.example` to `.env` (git-ignored) and fill in the blanks. `docker compose` **refuses to start** while
`DB_PASSWORD`, `JWT_SECRET`, `INTERNAL_API_KEY` or `ADMIN_PASSWORD` is empty. Nothing secret is stored in any image or
committed file: `.dockerignore` keeps `grandvista-local.properties` and `.env` out of every build context.

## 3. Native start-up

PostgreSQL must be running and the four databases must exist
(`grandvista_auth_db`, `grandvista_room_db`, `grandvista_booking_db`, `grandvista_payment_db` – see
`docs/database/pgadmin-setup.md` or `docs/database/01-create-databases.sql`). Tables are created by the services
(`ddl-auto=update`).

```powershell
# every terminal, once per window if Avast/other antivirus re-signs HTTPS:
$env:MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"

cd eureka-server;   mvn spring-boot:run      # 1  http://localhost:9000
cd auth-service;    mvn spring-boot:run      # 2
cd room-service;    mvn spring-boot:run      # 3
cd payment-service; mvn spring-boot:run      # 4
cd booking-service; mvn spring-boot:run      # 5
cd api-gateway;     mvn spring-boot:run      # 6  starts last so its first registry fetch is complete
cd grandvista-frontend; npm install; npm run dev    # 7  http://localhost:5173

# optional second Room Service for the load-balancing demo
cd room-service; mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8085"
```

Or import the six Maven projects into Spring Tool Suite and use the Boot Dashboard (README §13).

**Upgrading an existing database.** The booking service now has two more statuses. Hibernate's `ddl-auto=update` never
changes an existing CHECK constraint, so `BookingStatusConstraintUpdater` widens `bookings_status_check` once at start-up
(idempotent, only ever allows *more* values, touches no rows), and `payments` gains a nullable `success_booking_id`
column with a unique constraint. Existing bookings and payments are untouched. This was verified on a database created by
the previous version of the code.

**Review before your real database is touched.** The first start of the new booking-service and payment-service against an
existing database runs the equivalent of the statements below (Hibernate adds the column and constraint, the updater widens the
check). They only *add* or *relax*; no row is read, changed or deleted. Take a `pg_dump` first if you want a restore point, or run
the statements yourself in pgAdmin before starting the services.

```sql
-- grandvista_booking_db  (done by BookingStatusConstraintUpdater, only if the old 3-value constraint is still there)
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
  CHECK (status IN ('PENDING','PAYMENT_IN_PROGRESS','CONFIRMED','CANCELLATION_PENDING','CANCELLED'));

-- grandvista_payment_db  (done by Hibernate ddl-auto=update)
ALTER TABLE payments ADD COLUMN success_booking_id bigint;
ALTER TABLE payments ADD CONSTRAINT uk_payments_success_booking UNIQUE (success_booking_id);
-- Existing payments keep success_booking_id = NULL, which the unique constraint ignores. If you want existing
-- successful payments protected by the constraint too (optional), backfill after checking there are no duplicates:
--   SELECT booking_id FROM payments WHERE status IN ('SUCCESS','REFUNDED') GROUP BY booking_id HAVING count(*) > 1;
--   UPDATE payments SET success_booking_id = booking_id WHERE status IN ('SUCCESS','REFUNDED');
```

## 4. Docker Compose start-up (not yet executed – see the note at the top)

```bash
cp .env.example .env            # then edit .env: DB_PASSWORD, JWT_SECRET, INTERNAL_API_KEY, ADMIN_PASSWORD
docker compose config           # prints the resolved file; fails fast if a secret is missing
docker compose up --build -d
docker compose ps               # wait until every service is "healthy" (first start: 2–4 minutes)
```

| URL | What |
|---|---|
| http://localhost:5173 | React UI |
| http://localhost:8080/api/... | API Gateway (what the UI and Postman call) |
| http://localhost:9000 | Eureka dashboard – should list API-GATEWAY, AUTH-SERVICE, ROOM-SERVICE, BOOKING-SERVICE, PAYMENT-SERVICE |

Only those three ports are published. The business services and PostgreSQL are on the internal compose network, so their
`/internal/**` endpoints cannot be reached from the host. PostgreSQL data lives in the named volume `grandvista_pgdata`
– **not** in the PostgreSQL installed on your machine, whose data directory is never mounted.

Start-up order is enforced with health checks: PostgreSQL + Eureka → auth / room / payment → booking → gateway → frontend.

Second Room Service instance in Docker (load-balancing demo):
`docker compose --profile lb-demo up -d room-service-2`. It runs on port 8085 inside its container, so Eureka lists
`room-service:8082` and `room-service:8085` and the gateway / booking service balance across both. (`--scale
room-service=2` would **not** work: both replicas would register under the same instance id and overwrite each other.)

If it misbehaves, check in this order:

1. `docker compose logs <service>` – the first error line is usually a missing secret or a database login failure.
2. `docker compose ps` – a service stuck in "starting" for more than ~3 minutes has a failing health check; run
   `docker compose exec <service> curl -s localhost:<port>/actuator/health`.
3. Eureka dashboard – a service that started but is absent never registered: check `EUREKA_URL`.
4. Base image tags (`maven:3-eclipse-temurin-25`, `eclipse-temurin:25-jre`, `postgres:18-alpine`, `node:24-alpine`,
   `nginxinc/nginx-unprivileged:stable-alpine`) were confirmed to exist on Docker Hub when this was written.
5. If `docker/postgres/init-databases.sh` did not run, the volume already existed from an earlier attempt:
   `docker compose down -v` (this deletes **only** the compose volume) and start again.

## 5. Database initialisation

| Mode | How |
|---|---|
| Native | create the four empty databases once (pgAdmin guide / SQL script); the services create the tables |
| Docker | automatic on the first start of an empty volume (`docker/postgres/init-databases.sh`, idempotent) |

Demo data (4 hotels, 16 rooms, 4 pricing rules) is seeded by Room Service when its `hotels` table is empty and
`SEED_DEMO_DATA=true`. The administrator is created by Auth Service when no administrator exists and `ADMIN_PASSWORD` is set.
The seeded seasonal pricing rules carry fixed dates (festive season and year-end of the year of the first start); after
they pass, edit or add rules on the admin *Pricing* page.

## 6. Health checks

| Check | Command |
|---|---|
| gateway | `curl http://localhost:8080/actuator/health` |
| each service (native) | `curl http://localhost:8081/actuator/health` … `:8084` |
| registrations | open http://localhost:9000 or `curl -H "Accept: application/json" http://localhost:9000/eureka/apps` |
| end to end | `npx newman run postman/GrandVista.postman_collection.json -e postman/GrandVista-Local.postman_environment.json --env-var "adminPassword=<your ADMIN_PASSWORD>"` |

**Run the Postman collection only against a disposable stack.** It registers users, creates bookings and cancels them
– it is meant for the demo database or a throw-away one, not for data you care about.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `password authentication failed` although the properties file is right | a Windows user-level `DB_PASSWORD` overrides it – remove it or set the value in the STS run configuration |
| Maven `PKIX path building failed` | antivirus re-signs HTTPS: `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` |
| a service never appears in Eureka | it must reach `http://localhost:9000/eureka/` (`EUREKA_URL` in containers); registration can take ~30 s |
| booking returns 503 "… is currently unavailable" | Room or Payment Service is down or not yet registered; wait for Eureka (up to ~30 s) |
| after a payment outage a booking shows "Payment processing" | expected: it is resolved automatically within about a minute of the Payment Service coming back (recovery job); do not pay again |
| a cancelled booking shows "Cancelling" | the refund or room release is being retried every 30 s until it succeeds |
| port already in use | stop the old process or override: `AUTH_SERVICE_PORT`, `ROOM_SERVICE_PORT`, `BOOKING_SERVICE_PORT`, `PAYMENT_SERVICE_PORT`, `GATEWAY_PORT`, `EUREKA_PORT` |
| `/%69nternal/...` or any `/internal/...` request returns 403 | correct – internal endpoints need the internal key and are never routed by the gateway |

## 8. Shutdown

| Mode | Command |
|---|---|
| Native | Ctrl+C in each terminal (or *Stop* in the STS Boot Dashboard); stop the Vite dev server |
| Docker, keep data | `docker compose down` |
| Docker, delete the compose database volume | `docker compose down -v` (affects only the `grandvista_pgdata` volume) |

## 9. What this is not

A development/academic deployment: HS256 shared secret, JWT in `localStorage`, `ddl-auto=update` instead of migrations,
HTTP without TLS, a single PostgreSQL server for four databases, simulated payments. Passing a Docker build does not make
it production-ready; no cloud deployment has been performed.
