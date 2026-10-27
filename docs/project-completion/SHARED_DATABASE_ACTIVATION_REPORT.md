# GrandVista — Shared PostgreSQL Activation Report

Date: 2026-10-27. **Status: ACTIVATED and runtime-verified.** The Docker services now run against the four Windows PostgreSQL databases
(`host.docker.internal:5433`). Nothing was committed or pushed. No secret appears in this report.

## 1. Pre-flight

| Check | Result |
|---|---|
| Native STS services running? | **No.** Nothing listening on 8080–8085 / 9000 / 5173; the only Java process was the VS Code language server. |
| Other DB clients | Only idle pgAdmin sessions on the auth, booking and payment databases. |
| Isolated Docker stack | Was stopped (exited) before this task; its `postgres` container and volume `grandvista_pgdata` still exist, untouched. |
| Credentials | Existing local values (`grandvista-local.properties`, Windows PostgreSQL login) were copied into the **git-ignored** `.env` as `SHARED_DB_USERNAME`, `SHARED_DB_PASSWORD`, `SHARED_DB_PORT` (appended, nothing else edited). Never printed; password has no characters that break `.env` parsing. |

## 2. Fresh backups (taken before anything was started)

`pg_dump -Fc` (PostgreSQL 18.4) from the Windows server, timestamp `20261027-222125`, in `GrandVista-DB-Backups/`.
Each archive was validated with `pg_restore -l` (table + data entries present) and its embedded row count was compared with the live table.

| File | Bytes | SHA-256 (first 16) | Tables / data entries | Rows in archive = live |
|---|---|---|---|---|
| grandvista_auth_db-20261027-222125.backup | 3871 | a48d7d46ffaf58e8 | 1 / 1 | users 12 = 12 |
| grandvista_room_db-20261027-222125.backup | 13084 | 13d4d5071c6fcc82 | 4 / 4 | hotels 8, rooms 27, pricing_rules 8, room_holds 18 |
| grandvista_booking_db-20261027-222125.backup | 4946 | 386cd12b9fdac7ba | 1 / 1 | bookings 18 = 18 |
| grandvista_payment_db-20261027-222125.backup | 4792 | 9cbd937595edf699 | 1 / 1 | payments 18 = 18 |

The new files are byte-for-byte the same size as the 2026-10-24 backups, i.e. the data had not changed since. The archives contain real user e-mails and password hashes:
**do not commit them** (they are untracked; add them to `.gitignore` or keep them out of `git add`).

## 3. Schema changes (the two pending ones, nothing else)

| Database | Before | After (verified by catalog query) | Applied by |
|---|---|---|---|
| booking | `bookings_status_check` allowed 3 statuses | allows PENDING, PAYMENT_IN_PROGRESS, CONFIRMED, CANCELLATION_PENDING, CANCELLED | `BookingStatusConstraintUpdater` (log: "Widened bookings_status_check…") |
| payment | no `success_booking_id` | `success_booking_id bigint NULL` + `UNIQUE uk_payments_success_booking`; 0 of 18 rows populated (backfill deliberately **not** run) | Hibernate `ddl-auto=update` |
| auth, room | — | no change | — |

Why they are needed: the booking saga now persists two recovery states the old CHECK would reject, and payments get an idempotency key so one booking can never hold two
successful payments. Both changes only add or relax; no row was read-modified or deleted. The Hibernate log line `constraint "uk_payments_success_booking" … does not exist, skipping`
is the harmless "drop if exists" before creating it.

## 4. Configuration used

`docker compose -f docker-compose.yml -f docker-compose.shared-db.yml up -d --build` (config validated first with `config --quiet`).
Resolved config: 4 services with `DB_HOST=host.docker.internal`, `DB_PORT=5433`; no `postgres` service; `DEMO_MODE_ENABLED=false` (auth) and `SEED_DEMO_DATA=false` (room) —
also confirmed inside the running containers. The original `docker-compose.yml` and the isolated volume are unchanged.

## 5. Service health and Eureka

All seven containers up: eureka-server, auth, room, payment, booking, api-gateway = `healthy`; frontend `Up` (HTTP 200 on `/login`).
Eureka (`/eureka/apps`): AUTH-SERVICE `auth-service:8081` UP, ROOM-SERVICE `room-service:8082` UP, BOOKING-SERVICE `booking-service:8083` UP,
PAYMENT-SERVICE `payment-service:8084` UP. (The gateway did not appear in the registry listing; it only reads the registry and routes correctly, as the data served through it shows.) No `ERROR` lines in the logs.
Auth service logged "Administrator account already present - skipping admin bootstrap"; room-service did not seed (hotels already exist and the flag is off).

## 6. Record counts and integrity (read-only snapshots)

Counts plus an MD5 of every row's text, per table, before activation, after activation, and again after the API checks and 60 s of background schedulers:

| Table | Before | After | After API checks (+60 s) |
|---|---|---|---|
| auth.users | 12 | 12 | 12 (hash identical) |
| room.hotels | 8 | 8 | 8 (identical) |
| room.rooms | 27 | 27 | 27 (identical) |
| room.pricing_rules | 8 | 8 | 8 (identical) |
| room.room_holds | 18 | 18 | 18 (identical) |
| booking.bookings | 18 | 18 | 18 (identical) |
| payment.payments | 18 | 18 | 18 |

The payments hash differs only because the rows now carry the new (NULL) column; with that trailing NULL stripped the hash equals the "before" hash exactly
(`f50ef6e9…`), proving no existing payment value changed. Demo users in the auth database: 0.

## 7. Connectivity / API checks (read-only; no state-changing collection was run)

* Gateway `GET /api/hotels` → 8 (matches DB); `GET /api/rooms` → 18 (matches the 18 ACTIVE rooms of active hotels in the DB; the other 9 are inactive/maintenance).
* `GET /api/bookings/my` without a token → 401; `GET /api/auth/demo/accounts` → 403 (demo stays off).
* **Not verified:** authenticated admin/guest reads. Logging in with the `ADMIN_*` values in `.env` returned 401 — the real administrator's password is not the one in `.env`
  (the admin was created natively). I did not guess other passwords. Log in yourself at http://localhost:5173 to confirm bookings/payments appear (expected 18 each).
* Database-level connectivity of every service is proven by their healthy status, successful Hikari start, the schema changes that landed, and the data served through the gateway.

## 8. Errors

None during activation. One harmless Hibernate warning (§3). The `.env` admin-login 401 above is a credential mismatch, not a fault.

## 9. Risks now in force

* The real databases are now in use by Docker; **do not run STS microservices at the same time** (port clashes, duplicate schedulers, two Eureka registries). Stop Docker (`down`) before starting STS.
  Native services will accept the migrated schema (additive).
* The services use the `postgres` superuser; consider a limited role later.
* Tokens issued under Docker's `JWT_SECRET` are not valid under native secrets and vice versa (log in again).
* Background jobs (hold expiry, booking/payment recovery) now act on the real rows; counts were stable during verification.

## 10. Rollback

**Switch back to the isolated deployment (no data change):**
```bash
docker compose -f docker-compose.yml -f docker-compose.shared-db.yml down      # containers only; Windows databases untouched
docker compose up -d                                                           # isolated postgres + volume grandvista_pgdata (data kept)
```
**Return to native STS:** stop the Docker stack as above, then start the services from STS as before (the schema is forward-compatible).

**Undo the two schema changes (only if ever required; nothing was restored automatically):**
```sql
-- grandvista_payment_db  (safe: column is NULL for every row)
ALTER TABLE payments DROP CONSTRAINT uk_payments_success_booking;
ALTER TABLE payments DROP COLUMN success_booking_id;
-- grandvista_booking_db  (only valid while no booking is PAYMENT_IN_PROGRESS / CANCELLATION_PENDING)
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check CHECK (status IN ('PENDING','CONFIRMED','CANCELLED'));
```
Note: the previous application version recreates neither; the newer code needs the wider check, so only undo these if you also go back to older code.

**Full restore from backup (destructive — only if data is damaged; stop all services first):**
```powershell
$env:PGPASSWORD = "<windows-postgres-password>"
& "C:\Program Files\PostgreSQL\18\bin\pg_restore.exe" -h localhost -p 5433 -U postgres --clean --if-exists -d grandvista_booking_db GrandVista-DB-Backups\grandvista_booking_db-20261027-222125.backup
# repeat for auth / room / payment
```
