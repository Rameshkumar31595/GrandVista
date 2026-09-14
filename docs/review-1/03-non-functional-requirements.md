# 03 – Non-Functional Requirements

No numerical performance guarantees are claimed: the system has **not** been load-tested. Each requirement states
the design measure taken and how it can be verified.

| ID | Quality | Requirement | Design measures (implemented) | Verification |
|---|---|---|---|---|
| NFR-01 | **Security – authentication** | Only authenticated users may access personal data and transactions. | Stateless JWT (HS256, 256-bit+ key from configuration, `exp`, `iss`, `jti`); BCrypt password hashing; login error does not reveal whether email exists. | `JwtTokenProviderTest` (expired, tampered, wrong-key tokens rejected); `AuthServiceImplTest`. |
| NFR-02 | **Security – authorization** | Least privilege; admin functions unreachable for guests. | Role rules at gateway **and** in each service; ownership checks in service layer (bookings, payments, holds); registration cannot set role. | `GatewaySecurityIntegrationTest`, `*SecurityTest` in each service. |
| NFR-03 | **Security – internal surface** | Service-to-service operations must not be callable from browsers. | `/internal/**` not routed by gateway (`denyAll`); requires user JWT **plus** `X-Internal-Api-Key` (constant-time compare); gateway strips that header from clients. | `RoomSecurityTest#internalEndpointRejectsCallerWithoutServiceKeyEvenWithValidJwt`. |
| NFR-04 | **Security – secrets & data** | No secrets in source; no sensitive payment data. | Secrets only via env vars / git-ignored `grandvista-local.properties`; services refuse to start with missing/short JWT secret; payment stores method type only; logs never contain passwords/tokens. | Code review; `JwtTokenProviderTest#weakSecretIsRejectedAtStartup`. |
| NFR-05 | **Data integrity / reliability** | No double booking; no lost updates; no orphan blocked inventory. | Pessimistic row lock on hold creation; overlap query; `@Version` on bookings; row lock during payment; unique constraints (email, room number per hotel, transaction reference); hold expiry; compensating release/refund. | `ConcurrentHoldIntegrationTest` (fails when lock removed); `BookingServiceImplTest` compensation cases. |
| NFR-06 | **Availability** | Failure of one service must not take down unrelated functions. | Independent processes and databases; gateway timeouts (3 s connect, 15 s response); Booking client timeouts (3 s/10 s); unreachable service → clean **503** JSON; Room GETs retried at gateway on 502/503/504. | `RoomServiceClientTest#serverErrorBecomesServiceUnavailable`; stop Payment Service and try to pay. |
| NFR-07 | **Scalability** | Busy services can be scaled horizontally. | Stateless services (no HTTP session); Eureka registration with unique instance-id per port; `lb://` routing and load-balanced RestClient (round-robin). | Load-balancing demo in `10-review-demonstration-guide.md` (two Room Service instances). |
| NFR-08 | **Performance** | Common reads should be efficient. | Indexes on `room_holds(room_id, check_in, check_out)`, `bookings(user_id)`, `bookings(status)`, `payments(booking_id)`; `NOT EXISTS` sub-query for date search; `open-in-view=false`; entity graphs to avoid N+1 on room lists; row locks scoped to one room so different rooms never block each other. | Code review; SQL visible in pgAdmin. |
| NFR-09 | **Maintainability** | Code understandable and changeable by students. | Layered packages (controller → service → repository), DTO records, constructor injection, centralised exception handling, no Lombok, pricing rules as data. | Code structure; each service < ~40 classes. |
| NFR-10 | **Modularity** | Each business capability independently deployable. | Six separate Maven projects; database-per-service; communication only via REST contracts. | Each project builds and runs alone (`mvn spring-boot:run`). |
| NFR-11 | **Testability** | Business rules verifiable without infrastructure. | Pure `PriceCalculator` and `StayPeriod`; injectable `Clock`; H2 for JPA tests; MockMvc/WebTestClient security tests; `MockRestServiceServer` for HTTP clients. | `mvn test` needs no PostgreSQL. |
| NFR-12 | **Usability** | Simple flows for guests and staff. | Search → details → price breakdown → reserve → pay; countdown for payment deadline; field-level validation messages; role-aware navigation; responsive layout. | Manual UI walkthrough. |
| NFR-13 | **Observability** | Requests and important events traceable. | Gateway adds `X-Request-Id` and logs method/path/route/status/duration; services log registration, login, holds, bookings, payment results, compensations; actuator `health`/`info`. | Console logs during demo. |
| NFR-14 | **Portability** | Runs on any developer machine / STS. | No absolute paths; relative config import; standard Maven layout; Vite env variable for API URL. | Import into STS per README. |
| NFR-15 | **Compatibility** | Stable, mutually compatible versions only. | Spring Boot 4.0.8 + Spring Cloud 2025.1.3 (the BOM is built against Boot 4.0.8), springdoc 3.0.3, no milestones. | `pom.xml` files. |

## Honest limitations (academic vs production)

| Area | This project | Production-grade alternative |
|---|---|---|
| JWT signing | Symmetric HS256 shared by services | Asymmetric RS256/ES256 with JWKS endpoint, key rotation, OAuth2 authorization server |
| Token storage in browser | `localStorage` | httpOnly secure cookies + CSRF protection, or BFF pattern |
| Token revocation | Short expiry only (60 min) | Refresh tokens, revocation list |
| Distributed consistency | Orchestrated saga with synchronous compensations | Transactional outbox + message broker, retries, idempotency keys |
| Schema management | `ddl-auto=update` | Flyway/Liquibase migrations |
| Rate limiting | Not implemented | Gateway `RequestRateLimiter` with Redis |
| Transport | HTTP on localhost | TLS everywhere, mTLS between services |
| Config | Local properties file | Spring Cloud Config / Vault |
| Resilience | Timeouts + retry on GET | Circuit breakers (Resilience4j), bulkheads |
