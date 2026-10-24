#!/usr/bin/env bash
# Starts a DISPOSABLE verification environment:
#   * its own PostgreSQL server (own data directory, port 5543, random password) - NOT your PostgreSQL on 5433
#   * the six services (two Room Service instances) running from their jars against that server
# All secrets are generated randomly on the first run and stored in $GV_WORK_DIR/env.sh (outside the repository).
#
#   bash docs/project-completion/verification/start-isolated-stack.sh        # start (builds missing jars)
#   source "$GV_WORK_DIR/env.sh"                                             # then export the settings for the test scripts
#   bash docs/project-completion/verification/stop-isolated-stack.sh         # stop everything
#
# Needs: JDK 25, Maven (offline repository populated), PostgreSQL 14+ binaries (initdb, pg_ctl, createdb), curl, python.
# Windows: run from Git Bash. Set PG_BIN if the PostgreSQL bin directory is not found automatically.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
export GV_WORK_DIR="${GV_WORK_DIR:-${TMPDIR:-/tmp}/grandvista-verification}"
mkdir -p "$GV_WORK_DIR/logs" "$GV_WORK_DIR/shots"
ENV_FILE="$GV_WORK_DIR/env.sh"

find_pg_bin() {
  if [ -n "${PG_BIN:-}" ]; then echo "$PG_BIN"; return; fi
  if command -v initdb >/dev/null 2>&1; then dirname "$(command -v initdb)"; return; fi
  for d in "/c/Program Files/PostgreSQL/"*/bin /usr/lib/postgresql/*/bin; do
    if [ -x "$d/initdb" ] || [ -x "$d/initdb.exe" ]; then echo "$d"; return; fi
  done
  echo "PostgreSQL binaries (initdb, pg_ctl, createdb) not found - set PG_BIN" >&2; exit 1
}
PGB="$(find_pg_bin)"

random_b64() { python -c "import base64,secrets;print(base64.b64encode(secrets.token_bytes($1)).decode())"; }
random_hex() { python -c "import secrets;print(secrets.token_hex($1))"; }

# ---------------------------------------------------------------- secrets (generated once, never printed)
if [ ! -f "$ENV_FILE" ]; then
  DBPW="$(random_hex 12)"
  cat > "$ENV_FILE" <<EOF
# generated $(date -u +%FT%TZ) - disposable verification stack, safe to delete
export GV_WORK_DIR="$GV_WORK_DIR"
export DB_HOST=127.0.0.1 DB_PORT=5543 DB_USERNAME=gvtest DB_PASSWORD=$DBPW
export JWT_SECRET=$(random_b64 48)
export INTERNAL_API_KEY=$(random_hex 24)
export ADMIN_EMAIL=admin@grandvista.com ADMIN_PASSWORD=$(random_hex 12)
export JWT_EXPIRATION_MINUTES=60 CORS_ALLOWED_ORIGINS=http://localhost:5173 HOLD_MINUTES=15
# short recovery timers so failure-injection tests finish quickly (defaults: 60s / 30s)
export GRANDVISTA_BOOKING_PAYMENTRECOVERYAFTER=15s GRANDVISTA_BOOKING_CANCELLATIONRETRYAFTER=10s
export GV_LOG_DIR="$GV_WORK_DIR/logs" GV_SHOTS_DIR="$GV_WORK_DIR/shots" GV_SVC_SCRIPT="$HERE/svc.sh"
export GV_TEST_PG_URL=jdbc:postgresql://localhost:5543/grandvista_room_test_db GV_TEST_PG_USER=gvtest GV_TEST_PG_PASSWORD=$DBPW
EOF
  chmod 600 "$ENV_FILE" 2>/dev/null || true
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

# ---------------------------------------------------------------- PostgreSQL
PGDATA="$GV_WORK_DIR/pgdata"
if [ ! -d "$PGDATA" ]; then
  printf '%s' "$DB_PASSWORD" > "$GV_WORK_DIR/pwfile"
  "$PGB/initdb" -D "$PGDATA" -U "$DB_USERNAME" --pwfile="$GV_WORK_DIR/pwfile" -A scram-sha-256 -E UTF8 > "$GV_LOG_DIR/initdb.log" 2>&1
  rm -f "$GV_WORK_DIR/pwfile"
fi
if ! PGPASSWORD="$DB_PASSWORD" "$PGB/pg_isready" -h 127.0.0.1 -p "$DB_PORT" >/dev/null 2>&1; then
  # redirect everything: pg_ctl would otherwise keep this script's output pipe open
  "$PGB/pg_ctl" -D "$PGDATA" -o "-p $DB_PORT -c listen_addresses=127.0.0.1" -l "$GV_LOG_DIR/postgres.log" start > /dev/null 2>&1 &
fi
for i in $(seq 1 60); do
  PGPASSWORD="$DB_PASSWORD" "$PGB/pg_isready" -h 127.0.0.1 -p "$DB_PORT" >/dev/null 2>&1 && break
  sleep 1
done
for db in grandvista_auth_db grandvista_room_db grandvista_booking_db grandvista_payment_db grandvista_room_test_db; do
  PGPASSWORD="$DB_PASSWORD" "$PGB/createdb" -h 127.0.0.1 -p "$DB_PORT" -U "$DB_USERNAME" "$db" 2>/dev/null || true   # exists already
done
echo "PostgreSQL (disposable) ready on port $DB_PORT"

# ---------------------------------------------------------------- services
wait_health() { # port
  for i in $(seq 1 90); do
    [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://localhost:$1/actuator/health")" = "200" ] && return 0
    sleep 2
  done
  echo "service on port $1 did not become healthy - see $GV_LOG_DIR" >&2; return 1
}
start_service() { # name dir [extra java args]
  local name="$1" dir="$2"; shift 2
  [ -f "$ROOT/$dir/target/$dir-1.0.0.jar" ] || (cd "$ROOT/$dir" && mvn -q -o package -DskipTests)
  (cd "$ROOT/$dir" && nohup java -jar "target/$dir-1.0.0.jar" "$@" > "$GV_LOG_DIR/$name.log" 2>&1 &)
}

start_service eureka eureka-server;   wait_health 9000
start_service auth auth-service;      start_service room room-service; start_service payment payment-service
wait_health 8081; wait_health 8082; wait_health 8084
start_service booking booking-service; wait_health 8083
start_service gateway api-gateway;     wait_health 8080
start_service room2 room-service --server.port=8085; wait_health 8085
echo "Stack is up (gateway :8080, eureka :9000, rooms :8082/:8085). Logs: $GV_LOG_DIR"
echo "Next: source \"$ENV_FILE\""
