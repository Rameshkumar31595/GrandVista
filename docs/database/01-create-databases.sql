-- =====================================================================
-- GrandVista Hospitality - create the four microservice databases
-- =====================================================================
-- Run once, connected to the default "postgres" database as a user that
-- may create databases (e.g. in pgAdmin4: right-click "postgres" > Query Tool).
--
-- CREATE DATABASE cannot run inside a transaction block: in pgAdmin4's
-- Query Tool execute the statements one at a time if auto-commit is off.
--
-- One PostgreSQL server, one database per microservice (database-per-service).
-- Tables are created by each service on first start (spring.jpa.hibernate.ddl-auto=update).
-- =====================================================================

CREATE DATABASE grandvista_auth_db    ENCODING 'UTF8';
CREATE DATABASE grandvista_room_db    ENCODING 'UTF8';
CREATE DATABASE grandvista_booking_db ENCODING 'UTF8';
CREATE DATABASE grandvista_payment_db ENCODING 'UTF8';

-- Optional (recommended beyond the demo): a dedicated application login instead of the
-- "postgres" superuser. Replace the password, then set DB_USERNAME/DB_PASSWORD accordingly.
--
-- CREATE ROLE grandvista_app LOGIN PASSWORD 'choose-a-strong-password';
-- GRANT ALL PRIVILEGES ON DATABASE grandvista_auth_db    TO grandvista_app;
-- GRANT ALL PRIVILEGES ON DATABASE grandvista_room_db    TO grandvista_app;
-- GRANT ALL PRIVILEGES ON DATABASE grandvista_booking_db TO grandvista_app;
-- GRANT ALL PRIVILEGES ON DATABASE grandvista_payment_db TO grandvista_app;
-- PostgreSQL 15+ also requires schema privileges; run in EACH database:
-- GRANT ALL ON SCHEMA public TO grandvista_app;
