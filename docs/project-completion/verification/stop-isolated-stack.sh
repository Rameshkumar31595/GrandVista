#!/usr/bin/env bash
# Stops the services and the disposable PostgreSQL started by start-isolated-stack.sh.
# Does NOT delete $GV_WORK_DIR (delete that directory yourself to discard the disposable data and its random secrets).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export GV_WORK_DIR="${GV_WORK_DIR:-${TMPDIR:-/tmp}/grandvista-verification}"
[ -f "$GV_WORK_DIR/env.sh" ] || { echo "nothing to stop ($GV_WORK_DIR/env.sh not found)"; exit 0; }
# shellcheck disable=SC1090
source "$GV_WORK_DIR/env.sh"

for port in 8080 8085 8084 8083 8082 8081 9000; do bash "$HERE/svc.sh" stop "$port" || true; done

PGB="${PG_BIN:-}"
if [ -z "$PGB" ]; then
  if command -v pg_ctl >/dev/null 2>&1; then PGB="$(dirname "$(command -v pg_ctl)")"
  else for d in "/c/Program Files/PostgreSQL/"*/bin /usr/lib/postgresql/*/bin; do [ -x "$d/pg_ctl" ] || [ -x "$d/pg_ctl.exe" ] && PGB="$d" && break; done; fi
fi
[ -n "$PGB" ] && "$PGB/pg_ctl" -D "$GV_WORK_DIR/pgdata" stop -m fast > /dev/null 2>&1 && echo "disposable PostgreSQL stopped"
exit 0
