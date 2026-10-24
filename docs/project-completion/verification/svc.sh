#!/usr/bin/env bash
# Start / stop ONE service of the isolated verification stack (used by the failure-injection tests).
#   svc.sh stop 8084
#   svc.sh start payment payment-service [extra java args]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
: "${GV_WORK_DIR:?source the env.sh created by start-isolated-stack.sh first}"
# shellcheck disable=SC1090
source "$GV_WORK_DIR/env.sh"

port_pid() { # PID listening on a TCP port
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) netstat -ano 2>/dev/null | grep LISTENING | grep -E ":$1[[:space:]]" | awk '{print $NF}' | head -1 ;;
    *) ss -ltnp 2>/dev/null | grep ":$1 " | sed -E 's/.*pid=([0-9]+).*/\1/' | head -1 ;;   # Linux: not exercised
  esac
}

case "${1:-}" in
  stop)
    pid="$(port_pid "$2")"
    if [ -n "$pid" ]; then
      if command -v taskkill >/dev/null 2>&1; then taskkill //F //PID "$pid" >/dev/null 2>&1; else kill "$pid"; fi
      echo "stopped $2"
    fi ;;
  start)
    name="$2"; dir="$3"; shift 3
    (cd "$ROOT/$dir" && nohup java -jar "target/$dir-1.0.0.jar" "$@" > "$GV_LOG_DIR/$name.log" 2>&1 &)
    echo "started $name" ;;
  *) echo "usage: svc.sh stop PORT | svc.sh start NAME DIR [args]" >&2; exit 2 ;;
esac
