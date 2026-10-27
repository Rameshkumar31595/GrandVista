#!/usr/bin/env bash
# READ-ONLY preflight for "shared database" mode (Docker -> Windows PostgreSQL via host.docker.internal).
# Every session is opened with default_transaction_read_only=on, so nothing can be written even by mistake.
# Secrets are read from SHARED_DB_USERNAME / SHARED_DB_PASSWORD (environment, or the git-ignored .env) and never printed.
#
#   bash docs/project-completion/verification/shared-db-preflight.sh
# Exit code: 0 = reachable + all four databases present; 1 = a prerequisite is missing.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

envval() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2-; }
export SHARED_DB_USERNAME="${SHARED_DB_USERNAME:-$(envval SHARED_DB_USERNAME)}"
export PGPASSWORD="${SHARED_DB_PASSWORD:-$(envval SHARED_DB_PASSWORD)}"
PORT="${SHARED_DB_PORT:-$(envval SHARED_DB_PORT)}"; PORT="${PORT:-5433}"
IMAGE="postgres:18-alpine"
fail=0
say() { printf '%s\n' "$*"; }

[ -n "$SHARED_DB_USERNAME" ] && [ -n "$PGPASSWORD" ] || { say "MISSING: SHARED_DB_USERNAME / SHARED_DB_PASSWORD (set them in .env or the shell)"; exit 1; }
NET_ARGS=()
docker network inspect grandvista_grandvista >/dev/null 2>&1 && NET_ARGS=(--network grandvista_grandvista)

pg() { # pg <db> <psql args...>
  local db=$1; shift
  docker run --rm "${NET_ARGS[@]}" -e PGPASSWORD -e PGOPTIONS='-c default_transaction_read_only=on' \
    --add-host host.docker.internal:host-gateway "$IMAGE" \
    psql -h host.docker.internal -p "$PORT" -U "$SHARED_DB_USERNAME" -d "$db" -X -At -v ON_ERROR_STOP=1 "$@" 2>&1
}

say "1. server reachable from a container (host.docker.internal:$PORT)"
docker run --rm "${NET_ARGS[@]}" --add-host host.docker.internal:host-gateway "$IMAGE" pg_isready -h host.docker.internal -p "$PORT" || { say "   FAIL"; exit 1; }

say "2. login + read-only session + the four databases"
out="$(pg postgres -c "select 'version='||current_setting('server_version'), 'read_only='||current_setting('default_transaction_read_only')" -c "select datname from pg_database where datname like 'grandvista\_%\_db' order by 1")" || { say "   FAIL: $out" | sed 's/^/   /'; exit 1; }
say "$out" | sed 's/^/   /'
for db in auth room booking payment; do
  say "$out" | grep -qx "grandvista_${db}_db" || { say "   MISSING database grandvista_${db}_db"; fail=1; }
done

say "3. backups present in GrandVista-DB-Backups/"
for db in auth room booking payment; do
  f="GrandVista-DB-Backups/grandvista_${db}_db.backup"
  if [ -s "$f" ] && [ "$(head -c 5 "$f")" = "PGDMP" ]; then say "   ok   $f ($(wc -c <"$f") bytes, $(date -r "$f" +%F' '%R))"; else say "   MISSING or not a pg_dump archive: $f"; fail=1; fi
done

say "4. pending schema changes (additive; applied automatically on first start of the new services)"
c="$(pg grandvista_booking_db -c "select pg_get_constraintdef(oid) from pg_constraint where conname='bookings_status_check'")"
if echo "$c" | grep -q "PAYMENT_IN_PROGRESS" && echo "$c" | grep -q "CANCELLATION_PENDING"; then say "   booking : bookings_status_check already widened"; else say "   booking : bookings_status_check must be widened (3 -> 5 statuses)  [PENDING]"; fi
n="$(pg grandvista_payment_db -c "select count(*) from information_schema.columns where table_name='payments' and column_name='success_booking_id'")"
if [ "$n" = "1" ]; then say "   payment : success_booking_id already present"; else say "   payment : add payments.success_booking_id + unique uk_payments_success_booking  [PENDING]"; fi
d="$(pg grandvista_payment_db -c "select count(*) from (select booking_id from payments where status in ('SUCCESS','REFUNDED') group by 1 having count(*)>1) x")"
say "   payment : bookings with more than one successful payment = $d (must be 0 before the optional backfill)"
r="$(pg grandvista_auth_db -c "select count(*) filter (where role='ROLE_ADMIN') || ' admin(s), ' || count(*) || ' user(s), ' || count(*) filter (where email like '%@demo.grandvista.local') || ' demo user(s)' from users")"
say "   auth    : $r  (admin exists -> admin bootstrap will not run)"
h="$(pg grandvista_room_db -c "select count(*) from hotels")"
say "   room    : $h hotel(s)  (non-empty -> demo seeding will not run; it is also forced off in shared mode)"

[ $fail -eq 0 ] && say "RESULT: prerequisites satisfied (no data was modified)" || say "RESULT: prerequisite(s) missing - do not activate shared mode"
exit $fail
