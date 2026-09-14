# GrandVista Hospitality – Inventory Control & Dynamic Room Reservation Platform

University project for **SOA Programming and Microservices**. A Spring Boot / Spring Cloud microservices
backend with a React frontend that lets a (fictional) multi-property hotel chain manage room inventory, run
rule-based dynamic pricing, and accept collision-free bookings with simulated payments.

> Academic project: payments are **simulated**, and the security setup covers the fundamentals, not a production
> deployment. See `docs/review-1/03-non-functional-requirements.md` → *Honest limitations*.

---

## 1. Architecture

```
            React (Vite) :5173                     Postman
                     │  Bearer JWT                    │
                     └──────────────┬─────────────────┘
                                    ▼
                    API-GATEWAY :8080  (routing · JWT pre-check · CORS · X-Request-Id)
             lb://AUTH   lb://ROOM    lb://BOOKING    lb://PAYMENT
                │           │              │               │
          AUTH-SERVICE  ROOM-SERVICE  BOOKING-SERVICE  PAYMENT-SERVICE
             :8081        :8082    ◄───── :8083 ─────►    :8084
                │           │      load-balanced RestClient│
         auth_db       room_db        booking_db       payment_db     (one PostgreSQL server)

                     EUREKA-SERVER :9000 – every service registers and discovers the others
```

Full Mermaid diagrams (system, communication, booking sequence, JWT flow, database ownership):
[`docs/architecture/architecture-diagrams.md`](docs/architecture/architecture-diagrams.md)

| Service | Port | Responsibility | Database |
|---|---|---|---|
| eureka-server | 9000 | Service registry + dashboard | – |
| api-gateway | 8080 | Single entry point, routing, edge security, CORS | – |
| auth-service | 8081 | Registration, BCrypt passwords, JWT issuing, roles, admin bootstrap | `grandvista_auth_db` |
| room-service | 8082 | Hotels, rooms, date-aware holds, availability, dynamic pricing | `grandvista_room_db` |
| booking-service | 8083 | Booking lifecycle; Booking → Payment → Room orchestration | `grandvista_booking_db` |
| payment-service | 8084 | Simulated payments and refunds | `grandvista_payment_db` |
| grandvista-frontend | 5173 | React UI for guests and administrators | – |
| PostgreSQL 18 | 5433 | One server hosting the four databases | – |

Eureka dashboard: **http://localhost:9000** · clients register at `http://localhost:9000/eureka/` (property `eureka.client.service-url.defaultZone`).
Application API traffic (React, Postman) always goes through the API Gateway at **http://localhost:8080** (`/api/**`); port 9000 is used only by Eureka.
All Spring Boot configuration lives in `src/main/resources/application.properties` of each service.

## 2. Technology stack

| Layer | Technology (verified compatible) |
|---|---|
| Language | **Java 25** (LTS) |
| Build | Maven 3.9 (each service is an independent Maven project) |
| Framework | **Spring Boot 4.0.8** |
| Cloud | **Spring Cloud 2025.1.3**: Netflix Eureka 5.0.2, Gateway Server WebFlux 5.0.3, LoadBalancer 5.0.3 |
| Security | Spring Security 7, OAuth2 Resource Server (Nimbus JOSE, HS256 JWT), BCrypt |
| Persistence | Spring Data JPA / Hibernate 7, PostgreSQL 18 |
| Inter-service calls | Spring `RestClient` + Spring Cloud LoadBalancer interceptor (`DeferringLoadBalancerInterceptor`) |
| API docs | springdoc-openapi 3.0.3 (Swagger UI) |
| Tests | JUnit 5, Mockito, MockMvc, WebTestClient, MockRestServiceServer, H2 (in-memory) |
| Frontend | React 19.3, React Router 7.18, Vite 8.3 |

Why these versions: the Spring Cloud 2025.1.3 BOM is built against Spring Boot 4.0.8, and Boot 4.0 supports Java
17–25. Spring Boot 4.1 is out, but no stable Spring Cloud release supports it yet, so this project stays on 4.0.x.
The project uses no milestones and no Ribbon, Feign, Lombok or jjwt.

## 3. Folder structure

```
SOA-Project/
├── eureka-server/          Spring Boot – service registry
├── api-gateway/            Spring Boot – Spring Cloud Gateway (WebFlux)
├── auth-service/           Spring Boot – controller/service/repository/entity/dto/config/security/exception
├── room-service/           Spring Boot – + util (StayPeriod), pricing engine, schedulers
├── booking-service/        Spring Boot – + client (RoomServiceClient, PaymentServiceClient)
├── payment-service/        Spring Boot – simulated gateway
├── grandvista-frontend/    React + Vite (src/api, context, components, pages, pages/admin)
├── docs/
│   ├── requirements/       index + rubric traceability
│   ├── architecture/       Mermaid diagrams
│   ├── database/           pgAdmin guide, create-databases SQL, schema reference, demo queries
│   ├── api/                endpoint reference and routing
│   └── review-1/           01 … 10 rubric documents
├── postman/                collection + environment
├── grandvista-local.properties.example   configuration template (copy → grandvista-local.properties)
├── .gitignore
└── README.md
```

## 4. Prerequisites

| Tool | Version used | Check |
|---|---|---|
| JDK | 25 | `java -version` |
| Maven | 3.9.x | `mvn -version` |
| Node.js / npm | Node 20.19+ (tested 24.13) / npm 10+ | `node --version` |
| PostgreSQL | 18 (any 14+ should work) | pgAdmin4 |
| Spring Tool Suite | a release running on JDK 25 | – |

## 5. Database setup

Create four databases on your local PostgreSQL server:
`grandvista_auth_db`, `grandvista_room_db`, `grandvista_booking_db`, `grandvista_payment_db`.

Step-by-step pgAdmin4 instructions: [`docs/database/pgadmin-setup.md`](docs/database/pgadmin-setup.md)
(or run [`docs/database/01-create-databases.sql`](docs/database/01-create-databases.sql)).

Tables are created automatically on first start (`ddl-auto=update`, a development convenience). Room Service seeds
fictional demo data: 4 hotels in Hyderabad, Vijayawada, Bengaluru and Chennai, 16 rooms, and 4 pricing rules.

## 6. Configuration

All backend services read an optional, **git-ignored** file in the project root:

```bash
copy grandvista-local.properties.example grandvista-local.properties     # Windows
```

| Key | Required | Meaning / default |
|---|---|---|
| `DB_HOST` | – | `localhost` |
| `DB_PORT` | – | `5433` (this machine's PostgreSQL 18; use `5432` for a default install) |
| `DB_USERNAME` | **yes** | your PostgreSQL user |
| `DB_PASSWORD` | **yes** | its password |
| `JWT_SECRET` | **yes** | Base64 key ≥ 32 bytes, shared by auth, gateway and services (generation commands in the template) |
| `JWT_EXPIRATION_MINUTES` | – | `60` |
| `INTERNAL_API_KEY` | **yes** | random string shared by Booking, Room and Payment Service |
| `ADMIN_EMAIL` | – | `admin@grandvista.com` |
| `ADMIN_PASSWORD` | for admin login | password of the demo administrator, created at first start if no admin exists |
| `CORS_ALLOWED_ORIGINS` | – | `http://localhost:5173` |
| `HOLD_MINUTES` | – | `15` – payment window / room hold duration |
| `SEED_DEMO_DATA` | – | `true` |

Resolution: `spring.config.import=optional:file:../grandvista-local.properties` (in every `application.properties`). The path is relative to the
service folder, which is the working directory for both `mvn spring-boot:run` and STS. **Operating-system
environment variables override the file.** No secret is stored in Java source or in `application.properties`.

Frontend: `grandvista-frontend/.env.example` → optional `.env` with `VITE_API_BASE_URL=http://localhost:8080/api`.

### "password authentication failed" although the file is correct

An **operating-system environment variable** with the same name (for example a user-level `DB_PASSWORD` left
over from another project) **overrides** `grandvista-local.properties`. Check in PowerShell:

```powershell
[Environment]::GetEnvironmentVariable('DB_PASSWORD','User')   # prints a value? it wins over the file
```

Fix it one of three ways: remove or rename that Windows variable (*System Properties → Environment Variables*;
restart STS or the terminal afterwards), set the correct value in the STS run configuration's *Environment* tab,
or clear it for one terminal session with `Remove-Item Env:DB_PASSWORD`.

### Maven behind antivirus HTTPS scanning (e.g. Avast)

If Maven fails with `PKIX path building failed`, your antivirus is re-signing HTTPS traffic with its own root
certificate. Windows trusts that certificate, but Java does not. Tell Java to use the Windows certificate store:

```powershell
$env:MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"     # PowerShell, current window
```

For STS, add `-Djavax.net.ssl.trustStoreType=Windows-ROOT` on a new line after `-vmargs` in `SpringToolSuite4.ini`.
Once dependencies are in `~/.m2`, no further downloads are needed.

## 7. Build and test

```bash
cd auth-service && mvn test          # repeat for each backend folder
```

Tests need **no running PostgreSQL** (they use H2 or mocks):

| Service | Tests | Highlights |
|---|---|---|
| eureka-server | 1 | context starts |
| auth-service | 18 | JWT expiry/tampering/wrong key, BCrypt, role injection ignored, 401/403 |
| room-service | 40 | overlap rule table, pricing maths, repository queries, **8-thread double-booking test** |
| booking-service | 21 | success, payment failure, refund compensation, expiry, ownership, client JWT propagation |
| payment-service | 13 | success/failure/limit, duplicate payment, refunds |
| api-gateway | 8 | real gateway: 401/403, internal paths blocked, CORS |

Frontend: `cd grandvista-frontend && npm install && npm run build`.

## 8. Startup order

| # | Component | Command (terminal) | Ready when |
|---|---|---|---|
| 1 | PostgreSQL | Windows service running | pgAdmin connects |
| 2 | eureka-server | `mvn spring-boot:run` | <http://localhost:9000> opens |
| 3 | auth-service | `mvn spring-boot:run` | log `Started AuthServiceApplication` |
| 4 | room-service | `mvn spring-boot:run` | log `Demo data loaded` (first run) |
| 5 | payment-service | `mvn spring-boot:run` | started |
| 6 | booking-service | `mvn spring-boot:run` | started |
| 7 | api-gateway | `mvn spring-boot:run` | started; all 5 services in Eureka |
| 8 | frontend | `cd grandvista-frontend && npm install && npm run dev` | <http://localhost:5173> |

Business services only need Eureka to register. Payment and Room are started before Booking so the booking flow
works immediately. The gateway starts last so that it finds the other services in its first registry fetch.
Registration can take up to about 30 seconds to appear on the dashboard.

Health checks: `http://localhost:8080/actuator/health` (gateway), `http://localhost:808x/actuator/health`.

## 9. Expected URLs

| URL | What |
|---|---|
| <http://localhost:5173> | React application |
| <http://localhost:8080/api/...> | API Gateway (the only URL clients use) |
| <http://localhost:9000> | Eureka dashboard |
| <http://localhost:8081/swagger-ui.html> … `:8084` | Swagger UI per business service |

## 10. How to log in

* **Guest:** click *Register*, then log in. Every self-registered account is `ROLE_USER`.
* **Administrator:** `ADMIN_EMAIL` (default `admin@grandvista.com`) with the `ADMIN_PASSWORD` you configured.
  The account is created at the first Auth Service start only if no administrator exists. Admins can promote other
  users on the *Users* page.

## 11. Demonstrations

The full scripted walkthrough is in [`docs/review-1/10-review-demonstration-guide.md`](docs/review-1/10-review-demonstration-guide.md).

**User workflow:** Register → Login → Rooms (city + dates) → room → *Check availability & price* → *Reserve* →
payment page (UPI) → *Booking confirmed* → My bookings. Tick *simulate a declined payment* to see the failure path:
the booking is CANCELLED and the room is released.

**Admin workflow:** Login as admin → Dashboard (occupancy) → Hotels (add) → Rooms Admin (add/edit/status/
deactivate/reservations) → Pricing (base prices and seasonal/occupancy rules) → Bookings (all, cancel with refund)
→ Users (promote).

**JWT:** Postman folder *1. Auth* and *6. Security demos*: no token → 401, malformed or tampered token → 401,
USER on an admin endpoint → 403, direct service call without a token → 401. Paste a token into jwt.io to show its
claims. pgAdmin shows BCrypt hashes.

**Gateway:** all Postman calls use `http://localhost:8080/api`. Responses carry `X-Request-Id`, and the gateway log
shows the route taken. `/internal/**` and `/eureka/**` through the gateway → 403. The CORS preflight from
`localhost:5173` is allowed, and an unknown origin → 403.

**Eureka:** open <http://localhost:9000> → API-GATEWAY, AUTH-SERVICE, ROOM-SERVICE, BOOKING-SERVICE,
PAYMENT-SERVICE.

**Load balancing:** start a second Room Service on another port:

```bash
cd room-service
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8085
```

In STS, duplicate the run configuration and add the program argument `--server.port=8085`. Eureka then shows two
ROOM-SERVICE instances. Repeat `GET http://localhost:8080/api/rooms`: the response header `X-Instance-Id`
alternates between `room-service:8082` and `room-service:8085`. Booking Service calls are balanced the same way
through its load-balanced `RestClient`. Details: [`docs/review-1/05-service-discovery.md`](docs/review-1/05-service-discovery.md).

**Collision prevention:** book a room for day 30→33, then try the same room for day 32→35 → **409**. Booking day
33→35 is allowed, because the check-out day is free. Proof under concurrency:
`room-service` → `ConcurrentHoldIntegrationTest`.

## 12. Postman

Import `postman/GrandVista.postman_collection.json` and `postman/GrandVista-Local.postman_environment.json`,
select the **GrandVista Local** environment, set `adminPassword`, then run the folders in order (or use Collection
Runner). Variables: `baseUrl`, `gatewayUrl`, `userToken`, `adminToken`, `roomId`, `bookingId`, … Tokens and ids are
saved automatically by the test scripts. The collection has 49 requests and 72 assertions, and can be re-run on a
database that already contains data (it picks a free room each time).

Command line (Newman):

```bash
npx newman run postman/GrandVista.postman_collection.json -e postman/GrandVista-Local.postman_environment.json --env-var "adminPassword=<your ADMIN_PASSWORD>"
```

## 13. Importing into Spring Tool Suite (STS)

Each backend folder is a normal, independent Maven Spring Boot project (`pom.xml`, `src/main/java`,
`src/main/resources`, `src/test/java`). No VS Code settings are required.

1. Install/launch STS on **JDK 25**: *Window → Preferences → Java → Installed JREs → Add…* → select the JDK 25
   folder → tick it as default. *Java → Compiler → Compiler compliance level*: 25.
2. *File → Import… → Maven → Existing Maven Projects → Next*.
3. *Root Directory → Browse…* → select the **`SOA-Project`** folder. STS lists the six `pom.xml` files:
   `eureka-server`, `api-gateway`, `auth-service`, `room-service`, `booking-service`, `payment-service`.
   Tick all six → **Finish**. (You can also import a single service by selecting its folder.)
4. Wait for *Building workspace / Downloading Maven dependencies* to finish. If a project shows errors, right-click
   it → *Maven → Update Project… (Alt+F5)* → tick *Force Update* → OK.
5. Make sure `grandvista-local.properties` exists in the `SOA-Project` root (§6).
6. Run each application in the startup order (§8): right-click the project → **Run As → Spring Boot App**.
   (Or open the *Boot Dashboard*, select the apps, and press ▶.) The Console tab shows each service's log.
7. Environment variables instead of the file (optional): *Run → Run Configurations… → Spring Boot App →
   \<service\> → Environment → Add* (e.g. `DB_PASSWORD`).
8. Second Room Service instance: *Run Configurations…* → right-click `room-service` → *Duplicate* →
   *Arguments → Program arguments* `--server.port=8085` → Run.

The frontend is not an STS project. Run it from a terminal with `npm run dev`.

## 14. Documentation index

| Rubric | Documents |
|---|---|
| Problem analysis & requirements | `docs/review-1/01`, `02`, `03` |
| Microservices & service discovery | `docs/review-1/04`, `05` |
| JWT authentication | `docs/review-1/06` |
| API Gateway | `docs/review-1/07`, `docs/api/api-reference.md` |
| Supporting | `08-database-design`, `09-booking-workflow`, `10-review-demonstration-guide`, `docs/architecture`, `docs/database` |

## 15. Known limitations

HS256 shared secret (production: RS256 + JWKS); no refresh tokens or revocation; JWT kept in `localStorage`;
synchronous saga without an outbox (a crash between payment and confirmation needs reconciliation); no rate
limiting or circuit breakers; `ddl-auto=update` instead of migrations; HTTP without TLS on localhost. Details and
production alternatives are in `docs/review-1/03-non-functional-requirements.md` and `09-booking-workflow.md`.
