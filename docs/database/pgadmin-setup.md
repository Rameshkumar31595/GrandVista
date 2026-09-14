# pgAdmin4 Setup – GrandVista Databases

All four microservice databases live on **one local PostgreSQL server**. Each service owns exactly one database
and never connects to another service's database.

| Database | Owner service | Tables (created automatically) |
|---|---|---|
| `grandvista_auth_db` | AUTH-SERVICE | `users` |
| `grandvista_room_db` | ROOM-SERVICE | `hotels`, `rooms`, `room_holds`, `pricing_rules` |
| `grandvista_booking_db` | BOOKING-SERVICE | `bookings` |
| `grandvista_payment_db` | PAYMENT-SERVICE | `payments` |

## 1. Connect pgAdmin4 to your server

1. Open **pgAdmin4** → in the tree, expand **Servers**.
2. If your local server is not listed: right-click **Servers → Register → Server…**
   * *General → Name*: `Local PostgreSQL` (any name)
   * *Connection → Host*: `localhost`
   * *Connection → Port*: the port your server uses. The default is `5432`; **on the development machine used for
     this project PostgreSQL 18 listens on `5433`**. You can check in pgAdmin under
     *Server → Properties → Connection*, or in `postgresql.conf` (`port = …`).
   * *Connection → Username / Password*: your own PostgreSQL login (often `postgres` + the password chosen during
     installation). These values are **not** stored in this repository.

## 2. Create the four databases

**Option A – with the GUI (repeat four times)**

1. Right-click **Databases → Create → Database…**
2. *Database*: `grandvista_auth_db` → **Save**
3. Repeat for `grandvista_room_db`, `grandvista_booking_db`, `grandvista_payment_db`.

**Option B – with SQL**

1. Select the `postgres` database → **Tools → Query Tool**.
2. Open [`01-create-databases.sql`](01-create-databases.sql) and execute it (F5). If pgAdmin reports
   *"CREATE DATABASE cannot run inside a transaction block"*, highlight and run one statement at a time.

**Option C – command line** (Windows, from `C:\Program Files\PostgreSQL\18\bin`)

```bat
set PGPASSWORD=your_password
createdb -h localhost -p 5433 -U your_username grandvista_auth_db
createdb -h localhost -p 5433 -U your_username grandvista_room_db
createdb -h localhost -p 5433 -U your_username grandvista_booking_db
createdb -h localhost -p 5433 -U your_username grandvista_payment_db
```

Right-click **Databases → Refresh**: the four databases appear. They are empty until the services start.

## 3. Tell the services how to connect

Copy `grandvista-local.properties.example` (project root) to `grandvista-local.properties` and set:

| Variable | Meaning | Example |
|---|---|---|
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5433` (default in config) or `5432` |
| `DB_USERNAME` | your PostgreSQL user | *(your value)* |
| `DB_PASSWORD` | that user's password | *(your value)* |

Every service reads this file (`spring.config.import=optional:file:../grandvista-local.properties`).
Operating-system environment variables with the same names override the file.

## 4. Tables are created on first start

Each service runs with `spring.jpa.hibernate.ddl-auto=update`, so Hibernate creates the tables from the JPA
entities when the service starts. **This is a development convenience** – production systems use versioned
migrations (Flyway/Liquibase). The exact generated DDL is documented in
[`02-schema-reference.sql`](02-schema-reference.sql).

After starting the services, expand e.g. *grandvista_room_db → Schemas → public → Tables*.
Room Service also inserts fictional demo data (4 hotels, 16 rooms, 4 pricing rules) into an empty database.

## 5. Useful demo queries

[`03-useful-queries.sql`](03-useful-queries.sql) shows BCrypt hashes, active room holds, the overlap check and
the booking/payment history.

## Resetting the demo data

Stop the services, then in pgAdmin right-click each database → **Delete/Drop** and recreate it (step 2), or run
`DROP DATABASE grandvista_room_db;` etc. from the `postgres` database.
