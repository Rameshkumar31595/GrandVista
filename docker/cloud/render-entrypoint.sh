#!/bin/sh
# Container entrypoint for the CLOUD images only (docker/cloud/service.Dockerfile). The local images keep their own
# "java -jar" entrypoint and never run this script.
#
#  * EUREKA_URL: built from EUREKA_PUBLIC_URL (https://<eureka>.onrender.com) + EUREKA_USERNAME/EUREKA_PASSWORD.
#    The password is URL-encoded, so a generated Base64 value containing "/", "+" or "=" is safe in the URL.
#  * KEEP_WARM_URLS (gateway only): the /actuator/health URLs of every *_PUBLIC_URL given to the container.
#  * DB_BOOTSTRAP_DATABASE: creates this service's logical database on first start if it is missing.
#  * JVM sized for a 512 MB / 0.1 CPU free instance (override with JAVA_TOOL_OPTIONS_CLOUD).
# No value is ever printed.
set -eu

urlencode() {
  printf '%s' "$1" | sed -e 's/%/%25/g' -e 's/+/%2B/g' -e 's|/|%2F|g' -e 's/=/%3D/g' \
                         -e 's/:/%3A/g' -e 's/@/%40/g' -e 's/#/%23/g' -e 's/?/%3F/g' -e 's/ /%20/g'
}
trim_url() {   # drop a trailing slash
  printf '%s' "$1" | sed -e 's|/*$||'
}

if [ -z "${EUREKA_URL:-}" ] && [ -n "${EUREKA_PUBLIC_URL:-}" ]; then
  base=$(trim_url "$EUREKA_PUBLIC_URL")
  scheme=${base%%://*}
  host=${base#*://}
  creds=""
  if [ -n "${EUREKA_PASSWORD:-}" ]; then
    creds="$(urlencode "${EUREKA_USERNAME:-eureka}"):$(urlencode "$EUREKA_PASSWORD")@"
  fi
  EUREKA_URL="${scheme}://${creds}${host}/eureka/"
  export EUREKA_URL
fi

if [ -z "${KEEP_WARM_URLS:-}" ] && [ "${KEEP_WARM_ENABLED:-true}" = "true" ]; then
  list=""
  for target in "${EUREKA_PUBLIC_URL:-}" "${AUTH_PUBLIC_URL:-}" "${ROOM_PUBLIC_URL:-}" "${ROOM2_PUBLIC_URL:-}" \
                "${BOOKING_PUBLIC_URL:-}" "${PAYMENT_PUBLIC_URL:-}"; do
    [ -n "$target" ] || continue
    list="${list:+$list,}$(trim_url "$target")/actuator/health"
  done
  # only the gateway receives the service URLs; for the other services the list stays empty (feature off)
  if [ -n "${AUTH_PUBLIC_URL:-}" ]; then
    KEEP_WARM_URLS="$list"
    export KEEP_WARM_URLS
  fi
fi

# One logical database per service inside the single free PostgreSQL instance: create this service's database on first
# start if it does not exist (idempotent; uses the instance owner login, never drops or alters anything).
if [ -n "${DB_BOOTSTRAP_DATABASE:-}" ] && command -v psql >/dev/null 2>&1; then
  case "$DB_BOOTSTRAP_DATABASE" in
    *[!a-z0-9_]*) echo "render-entrypoint: DB_BOOTSTRAP_DATABASE must match [a-z0-9_]+ - skipped" >&2 ;;
    *)
      conn="host=${DB_HOST:-} port=${DB_PORT:-5432} user=${DB_USERNAME:-} dbname=${DB_ADMIN_DATABASE:-postgres} sslmode=${DB_SSLMODE:-prefer} connect_timeout=20"
      if exists=$(PGPASSWORD="${DB_PASSWORD:-}" psql "$conn" -v ON_ERROR_STOP=1 -tAc             "SELECT 1 FROM pg_database WHERE datname = '$DB_BOOTSTRAP_DATABASE'" 2>/dev/null); then
        if [ "$exists" != "1" ]; then
          if PGPASSWORD="${DB_PASSWORD:-}" psql "$conn" -v ON_ERROR_STOP=1 -qc                "CREATE DATABASE \"$DB_BOOTSTRAP_DATABASE\" ENCODING 'UTF8'" >/dev/null 2>&1; then
            echo "render-entrypoint: created database $DB_BOOTSTRAP_DATABASE"
          else
            echo "render-entrypoint: could not create $DB_BOOTSTRAP_DATABASE (another instance may have just created it)" >&2
          fi
        fi
      else
        echo "render-entrypoint: database bootstrap check failed (PostgreSQL not reachable yet?) - continuing" >&2
      fi
      ;;
  esac
fi

JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS_CLOUD:--XX:+UseSerialGC -XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError}"
export JAVA_TOOL_OPTIONS
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-cloud}"

exec java -jar /app/app.jar
