#!/bin/bash
# Runs once, the first time the PostgreSQL volume is created: one database per microservice.
# Idempotent - an existing database is left alone. Tables are created by each service (ddl-auto=update).
set -euo pipefail

for db in grandvista_auth_db grandvista_room_db grandvista_booking_db grandvista_payment_db; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
    SELECT 'CREATE DATABASE $db ENCODING ''UTF8''' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
EOSQL
done
