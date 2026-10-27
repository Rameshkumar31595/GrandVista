#!/usr/bin/env bash
# Verifies that LOCAL_REVIEW mode is loopback-only. Read-only: GET requests and `docker` inspection only.
#   bash docs/project-completion/verification/local-review-check.sh
# Exit 0 = every published port is bound to 127.0.0.1 and is unreachable via this machine's LAN addresses.
set -uo pipefail
fail=0
echo "1. published port bindings"
bindings="$(docker ps --format '{{.Names}} {{.Ports}}' | grep -E 'api-gateway|frontend|eureka-server')"
echo "$bindings" | sed 's/^/   /'
if echo "$bindings" | grep -qE '0\.0\.0\.0:|\[::\]:'; then echo "   FAIL: a port is bound to all interfaces"; fail=1; else echo "   ok: loopback only"; fi
echo "2. loopback works, LAN addresses are refused"
curl -s -m 5 -o /dev/null -w "   127.0.0.1:8080/actuator/health -> %{http_code}\n" http://127.0.0.1:8080/actuator/health
for ip in $(ipconfig 2>/dev/null | grep -E "IPv4" | sed 's/.*: //' | tr -d '\r') $(hostname -I 2>/dev/null); do
  for port in 8080 5173 9000; do
    code="$(curl -s -m 4 -o /dev/null -w '%{http_code}' "http://$ip:$port/" || true)"
    if [ "$code" = "000" ] || [ -z "$code" ]; then echo "   $ip:$port -> refused (good)"; else echo "   $ip:$port -> HTTP $code  FAIL (reachable from outside)"; fail=1; fi
  done
done
echo "3. demo mode state"
curl -s -m 5 -o /dev/null -w "   GET /api/auth/demo/accounts -> %{http_code}  (200 = demo on, 403 = demo off)\n" http://127.0.0.1:8080/api/auth/demo/accounts
[ $fail -eq 0 ] && echo "RESULT: loopback-only confirmed" || echo "RESULT: NOT loopback-only - do not enable passwordless demo login"
exit $fail
