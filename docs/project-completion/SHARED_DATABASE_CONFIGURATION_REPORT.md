# GrandVista — Shared PostgreSQL Configuration Report

Date: 2026-10-27. **Shared mode is configured and verified but NOT activated.** No service was started against the Windows
databases, nothing was migrated, no row was changed, nothing was committed or pushed.

## 1. Result at a glance

| Item | Status |
|---|---|
| Docker container → Windows PostgreSQL (`host.docker.internal:5433`) | reachable, authenticates (tested from the compose network) |
| Four databases present, UTF8, PostgreSQL 18.4 | yes |
| Backups present | yes, all four (see §3) |
| Schema compatible | **yes, after two additive changes** (§4), applied automatically on first start |
| Reversible Compose override | `docker-compose.shared-db.yml` (new); base `docker-compose.yml` untouched |
| Isolated mode preserved | yes (`pgdata` volume and container kept) |
| Secrets in files/report | none |

## 2. Configuration inspected

* Native (STS): each service uses `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5433}/grandvista_<svc>_db`, credentials from the git-ignored
  `grandvista-local.properties` (host `localhost`, port 5433, login `postgres`). `ddl-auto=update` in all four services.
* Docker (base): `postgres:18-alpine` container, `DB_HOST=postgres`, `DB_PORT=5432`, login from `.env` (`grandvista`), volume `grandvista_pgdata`,
  databases created by `docker/postgres/init-databases.sh`.
* Windows server: PostgreSQL 18.4, `port=5433`, `listen_addresses='*'`, `pg_hba.conf` allows only 127.0.0.1/::1 (scram-sha-256).
  Docker Desktop delivers `host.docker.internal` traffic from the host loopback side, so no `pg_hba.conf` change was needed — verified from a
  container on the compose network. (On a Linux Docker engine this would need a `pg_hba.conf` entry for the Docker subnet; not the case here.)

## 3. Existing databases and backups

Read-only counts (sessions forced to `default_transaction_read_only=on`): auth 12 users (1 admin, 0 demo); room 8 hotels / 27 rooms / 8 pricing rules / 18 holds;
booking 18 bookings (11 CANCELLED, 7 CONFIRMED); payment 18 payments.

Backups in `GrandVista-DB-Backups/` (custom-format `pg_dump`, PostgreSQL 18.4, readable with `pg_restore -l`):

| File | Archive created | Contains |
|---|---|---|
| grandvista_auth_db.backup | 2026-10-24 12:49 | users |
| grandvista_room_db.backup | 2026-10-24 12:50 | hotels, pricing_rules, room_holds, rooms |
| grandvista_booking_db.backup | 2026-10-24 12:50 | bookings |
| grandvista_payment_db.backup | 2026-10-24 12:50 | payments |

They are three days old and the databases have been used since (the live booking count may differ from the dump). **Take a fresh backup (§6, step 2) before activating.**

## 4. Schema compatibility and pending migrations

Comparing the live schema with the code (only Booking/Payment entities changed since the databases were created; no new columns in any other table):

| Database | Pending change | Done by | Nature |
|---|---|---|---|
| grandvista_booking_db | `bookings_status_check` still allows 3 statuses; code needs 5 (`PAYMENT_IN_PROGRESS`, `CANCELLATION_PENDING`) | `BookingStatusConstraintUpdater` on booking-service start | drop + re-add a **wider** CHECK; no rows touched |
| grandvista_payment_db | column `success_booking_id` and unique `uk_payments_success_booking` missing | Hibernate `ddl-auto=update` on payment-service start | adds a nullable column + unique (NULLs ignored); no rows touched |
| auth, room | none | — | — |

Checks done: no booking has more than one SUCCESS/REFUNDED payment (0 duplicates), so the optional backfill of `success_booking_id` is safe if wanted (SQL in
`DEPLOYMENT_GUIDE.md` §3). Skipping the backfill is safe; old payments simply are not covered by the new uniqueness guard.
The SQL can also be run by hand in pgAdmin *before* the first start if you prefer to review it.

## 5. What was added

* `docker-compose.shared-db.yml` — override file. In shared mode it: points auth/room/payment/booking (and the optional `room-service-2`) at
  `host.docker.internal:${SHARED_DB_PORT:-5433}` with `SHARED_DB_USERNAME` / `SHARED_DB_PASSWORD` (required; compose refuses to start without them);
  disables the isolated `postgres` container via profile `isolated-db` and removes the dependencies on it; adds `extra_hosts: host.docker.internal:host-gateway`;
  **forces `DEMO_MODE_ENABLED=false` and `SEED_DEMO_DATA=false`** so no demo account or demo hotel can be written into the real databases.
* `.env.example` — commented `SHARED_DB_*` block (real values go in your git-ignored `.env`).
* `docs/project-completion/verification/shared-db-preflight.sh` — repeatable read-only preflight (reachability, databases, backups, pending migrations).

Validation performed: `docker compose config` passes for the base file; the shared combination fails fast without `SHARED_DB_*`, and with placeholder values resolves to the
expected host/port, demo flags `false`, no `postgres` service or dependency, `extra_hosts` on all four DB services. The preflight script ran successfully against the real server.

## 6. Commands

**Before activating (once)**

```bash
# 1. repeat the read-only check any time (needs SHARED_DB_USERNAME / SHARED_DB_PASSWORD in .env or the shell)
bash docs/project-completion/verification/shared-db-preflight.sh

# 2. fresh backups (read-only on the server). PowerShell example, prompts for nothing - uses $env:PGPASSWORD:
$env:PGPASSWORD = "<windows-postgres-password>"
$stamp = Get-Date -Format yyyyMMdd-HHmm
foreach ($d in "auth","room","booking","payment") {
  & "C:\Program Files\PostgreSQL\18\bin\pg_dump.exe" -h localhost -p 5433 -U postgres -Fc -f "GrandVista-DB-Backups\grandvista_${d}_db-$stamp.backup" "grandvista_${d}_db"
}

# 3. add the credentials to .env (git-ignored), never to a tracked file:
#    SHARED_DB_USERNAME=...   SHARED_DB_PASSWORD=...
```

**Switch to SHARED mode (Windows PostgreSQL)**

```bash
# stop the native STS services first (same ports 8080-8085/9000 and the same databases; two stacks must never run against one DB)
docker compose down                      # optional: removes isolated containers; the named volume grandvista_pgdata is KEPT
docker compose -f docker-compose.yml -f docker-compose.shared-db.yml config --quiet      # validate
docker compose -f docker-compose.yml -f docker-compose.shared-db.yml up -d --build       # first start performs the two additive migrations
docker compose -f docker-compose.yml -f docker-compose.shared-db.yml ps
```

**Switch back to ISOLATED mode**

```bash
docker compose -f docker-compose.yml -f docker-compose.shared-db.yml down     # containers only; Windows databases untouched
docker compose up -d                                                           # base file only -> isolated postgres + pgdata volume
```

**Roll back the migrations (only if ever needed)** — restore from the fresh backup with `pg_restore --clean --if-exists -d <db> <file>` for the affected database,
or reverse manually: `ALTER TABLE payments DROP CONSTRAINT uk_payments_success_booking, DROP COLUMN success_booking_id;` and re-create the 3-status `bookings_status_check`
(only valid while no booking is in one of the two new states).

## 7. Risks

1. **First start modifies the real schema** (two additive changes, §4). Mitigation: fresh backup, optional manual SQL. Not reversible by simply switching modes.
2. **Backups are 3 days old.** Take new ones (step 2).
3. **Never run STS and Docker services at once** against the same databases: duplicate port use, two Eureka registries, and two sets of booking-recovery / hold-expiry
   schedulers working on the same rows.
4. **Superuser login.** The native config uses the `postgres` superuser; shared mode will run the services with whatever login you put in `SHARED_DB_*`. A dedicated
   least-privilege role would be safer (not created — it would modify the server).
5. **JWT secret / internal key differ** between native (`grandvista-local.properties`) and Docker (`.env`): tokens from one mode are rejected by the other — users just log in again.
6. **Windows user-level `DB_PASSWORD`** (from another project) overrides the local file for STS runs; shared mode uses `SHARED_DB_PASSWORD` explicitly, so it is unaffected.
7. `pg_hba.conf` currently relies on Docker Desktop's loopback delivery; a different Docker engine or a changed `pg_hba.conf` would break connectivity (preflight detects it).
8. The Docker stack was found stopped during this work (not stopped by me); nothing was restarted.

## 8. Not done on purpose

No service was started against the Windows databases, no DDL/DML was executed, `.env` and `grandvista-local.properties` were not edited, and no commit was made.
