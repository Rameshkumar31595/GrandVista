-- OPTIONAL, CLOUD PostgreSQL only: hand every table/sequence of the CURRENT database's public schema to the service
-- login given as -v svc=... (see create-service-logins.sql). Touches nothing outside the current database.
--
--   psql "<EXTERNAL_DATABASE_URL with /grandvista_room_db at the end>" -v ON_ERROR_STOP=1 -v svc=gv_room \
--        -f docker/cloud/move-tables-to-service-login.sql

\set ON_ERROR_STOP on

SELECT format('ALTER TABLE public.%I OWNER TO %I', tablename, :'svc')
FROM pg_tables WHERE schemaname = 'public'
\gexec

SELECT format('ALTER SEQUENCE public.%I OWNER TO %I', sequencename, :'svc')
FROM pg_sequences s
WHERE schemaname = 'public'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d JOIN pg_class c ON c.oid = d.objid
                  WHERE c.relname = s.sequencename AND d.deptype IN ('a', 'i'))
\gexec
