-- OPTIONAL hardening for the CLOUD PostgreSQL only (never run this against the Windows PostgreSQL on port 5433).
--
-- Gives every microservice its own login that can connect ONLY to its own database, so e.g. the room service cannot
-- read grandvista_auth_db even though all four databases live in the same free instance.
--
-- Run with psql using the Render "External Database URL" (owner login). Passwords are passed on the command line and
-- are not stored in this file:
--
--   psql "<EXTERNAL_DATABASE_URL>" -v ON_ERROR_STOP=1 \
--        -v auth_pw='...' -v room_pw='...' -v booking_pw='...' -v payment_pw='...' \
--        -f docker/cloud/create-service-logins.sql
--
-- Then, per service in the Render dashboard, set <SVC>_DB_USERNAME / <SVC>_DB_PASSWORD (e.g. AUTH_DB_USERNAME=gv_auth)
-- and redeploy. The four databases must already exist (each service creates its own on first start).
-- Requires the owner login to have CREATEROLE; if Render refuses, keep using the owner login (documented limitation).
-- Re-runnable: existing logins only get their password reset.

\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', r.name, r.pw)
FROM (VALUES ('gv_auth', :'auth_pw'), ('gv_room', :'room_pw'), ('gv_booking', :'booking_pw'),
             ('gv_payment', :'payment_pw')) AS r(name, pw)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.name)
\gexec

SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', r.name, r.pw)
FROM (VALUES ('gv_auth', :'auth_pw'), ('gv_room', :'room_pw'), ('gv_booking', :'booking_pw'),
             ('gv_payment', :'payment_pw')) AS r(name, pw)
\gexec

-- PostgreSQL 16+: the owner must be a member of a role to hand it a database.
SELECT format('GRANT %I TO CURRENT_USER', r.name)
FROM (VALUES ('gv_auth'), ('gv_room'), ('gv_booking'), ('gv_payment')) AS r(name)
\gexec

SELECT format('ALTER DATABASE %I OWNER TO %I', d.db, d.owner)
     || format('; REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', d.db)
     || format('; GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', d.db, d.owner)
FROM (VALUES ('grandvista_auth_db', 'gv_auth'), ('grandvista_room_db', 'gv_room'),
             ('grandvista_booking_db', 'gv_booking'), ('grandvista_payment_db', 'gv_payment')) AS d(db, owner)
\gexec

-- If the services already ran with the owner login, their tables still belong to it. Move them (per database), e.g.:
--   psql "<EXTERNAL_DATABASE_URL with /grandvista_room_db at the end>" -v svc=gv_room -f docker/cloud/move-tables-to-service-login.sql
