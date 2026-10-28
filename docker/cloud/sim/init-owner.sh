#!/bin/bash
# Cloud simulation only: create a NON-superuser instance owner (CREATEDB, CREATEROLE), like the owner login of a managed
# PostgreSQL instance, so the database bootstrap and create-service-logins.sql are exercised without superuser rights.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v owner="$SIM_OWNER_USER" -v pw="$SIM_OWNER_PASSWORD" <<'EOSQL'
SELECT format('CREATE ROLE %I LOGIN CREATEDB CREATEROLE PASSWORD %L', :'owner', :'pw')\gexec
SELECT format('CREATE DATABASE grandvista_cloud OWNER %I', :'owner')\gexec
EOSQL
