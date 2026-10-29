#!/bin/bash
# Start / stop the LOCAL simulation of the Render deployment (docker-compose.cloud-sim.yml).
#
#   bash docker/cloud/sim/run-cloud-sim.sh up   [secrets-file]   # default secrets file: ${TMPDIR:-/tmp}/grandvista-cloudsim.env
#   bash docker/cloud/sim/run-cloud-sim.sh down [secrets-file]
#
# "up" generates throw-away random secrets (only if the secrets file does not exist yet), builds the cloud images and
# waits until every service is healthy. The secrets file lives OUTSIDE the repository and is never committed.
# "down" removes only this project's containers and network (there are no volumes: PostgreSQL runs in tmpfs).
set -euo pipefail

cd "$(dirname "$0")"
action=${1:-up}
secrets=${2:-${TMPDIR:-/tmp}/grandvista-cloudsim.env}
compose=(docker compose -p grandvista-cloudsim -f docker-compose.cloud-sim.yml --env-file "$secrets")

rand() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
alnum() { head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c "$1"; }

if [ ! -f "$secrets" ]; then
  umask 077
  {
    echo "SIM_JWT_SECRET=$(rand 48)"
    echo "SIM_INTERNAL_API_KEY=$(rand 32)"
    # deliberately Base64 (may contain / + =) to prove the entrypoint URL-encodes the Eureka password
    echo "SIM_EUREKA_PASSWORD=$(rand 32)"
    echo "SIM_DB_PASSWORD=$(alnum 24)"
    echo "SIM_SUPER_PASSWORD=$(alnum 24)"
    echo "SIM_ADMIN_PASSWORD=Adm1n-$(alnum 12)"
    echo "SIM_DEMO_CODE=review-$(alnum 16)"
  } > "$secrets"
  echo "generated throw-away secrets in $secrets"
fi

case "$action" in
  up)
    "${compose[@]}" up -d --build
    echo "waiting for all services to become healthy ..."
    for _ in $(seq 1 120); do
      # -a: a crashed (exited) container must count as "not healthy", not disappear from the list
      unhealthy=$("${compose[@]}" ps -a --format '{{.Service}} {{.State}} {{.Health}}' \
        | awk '$1 != "web" && $1 != "postgres" && !($2 == "running" && $3 == "healthy") {print $1 "(" $2 ")"}')
      exited=$("${compose[@]}" ps -a --format '{{.Service}} {{.State}}' | awk '$2 == "exited" {print $1}')
      [ -n "$exited" ] && { echo "crashed: $exited" >&2; "${compose[@]}" ps -a; exit 1; }
      [ -z "$unhealthy" ] && { echo "all services healthy"; "${compose[@]}" ps; exit 0; }
      sleep 5
    done
    echo "still not healthy: $unhealthy" >&2
    "${compose[@]}" ps
    exit 1
    ;;
  down)
    "${compose[@]}" --profile lb-demo down --remove-orphans
    ;;
  *)
    "${compose[@]}" "$@"
    ;;
esac
