# 04 – Microservice Identification

## 1. Method

Services were identified with **Domain-Driven Design** (bounded contexts) and checked against the rule
"one business capability, one owner of its data":

1. List business capabilities from the functional requirements (FR-01 … FR-30).
2. Group capabilities that change together and share the same data (high cohesion).
3. Split groups whose data has different owners, lifecycles or scaling needs (loose coupling).
4. Give each group its own database; allow only REST contracts between groups.
5. Add infrastructure services required by the architecture (discovery, edge).

## 2. Capability → bounded context mapping

| Capability | Data it owns | Bounded context |
|---|---|---|
| Registration, login, roles, token issuing | users, password hashes | **Identity** |
| Hotels, rooms, status, base prices | hotels, rooms | **Inventory & Pricing** |
| Date availability, reservation holds | room_holds | **Inventory & Pricing** |
| Pricing rules and price calculation | pricing_rules | **Inventory & Pricing** |
| Booking lifecycle, workflow orchestration | bookings | **Reservations** |
| Payment transactions, refunds | payments | **Payments** |

### Why availability and pricing live with rooms (not in Booking Service)

* **Collision prevention needs one owner.** The overlap check and the insert of a hold must happen in *one local
  transaction under one lock*. If holds were in Booking Service and rooms in Room Service, that atomic check
  would span two databases.
* Price depends on room base price **and** occupancy of the hotel (derived from holds) – all in the same database.

### Why Booking and Payment are separate

* Different lifecycles (a booking lives for months; a payment is a transaction record with refunds).
* Different compliance/scaling profile – in a real system payments are isolated for audit and PCI reasons.
* Replaceable: the simulated gateway can be swapped for a real provider without touching bookings.

## 3. Final service catalogue

| # | Service (Eureka name) | Port | Responsibility | Owns DB | Calls | Called by |
|---|---|---|---|---|---|---|
| 1 | **EUREKA-SERVER** (`eureka-server`) | 9000 | Service registry and dashboard | – | – | all services |
| 2 | **API-GATEWAY** | 8080 | Single entry point: routing, JWT pre-validation, role checks, CORS, request id, retries | – | all business services | React, Postman |
| 3 | **AUTH-SERVICE** | 8081 | Users, BCrypt passwords, JWT issuing, admin bootstrap, role management | `grandvista_auth_db` | – | Gateway |
| 4 | **ROOM-SERVICE** | 8082 | Hotels, rooms, date-aware holds, availability, dynamic pricing, inventory summary | `grandvista_room_db` | – | Gateway, Booking |
| 5 | **BOOKING-SERVICE** | 8083 | Booking lifecycle and Booking → Payment → Room orchestration, expiry, cancellation | `grandvista_booking_db` | Room, Payment | Gateway |
| 6 | **PAYMENT-SERVICE** | 8084 | Simulated payment processing, refunds, payment history | `grandvista_payment_db` | – | Gateway (read), Booking |
| 7 | **grandvista-frontend** | 5173 | React SPA for guests and admins | – | Gateway only | Browser |

## 4. Internal structure of a business service

Every business service uses the same layered package layout, so a reviewer can navigate any of them:

```
com.grandvista.roomservice
├── RoomServiceApplication        @SpringBootApplication
├── controller                    REST endpoints only (no business logic)
├── service                       interfaces + pure domain helpers (PriceCalculator, schedulers)
│   └── impl                      business logic, transactions
├── repository                    Spring Data JPA interfaces, JPQL/Specifications
├── entity                        JPA entities and enums (never exposed as request bodies)
├── dto                           request/response records with Bean Validation
├── config                        security, properties, OpenAPI, seed data, clock
├── security                      JSON 401/403 writer, internal API-key filter
├── exception                     ApiError + @RestControllerAdvice
└── util                          StayPeriod, AuthenticatedUser
```

Booking Service additionally has `client` (+ `client.dto`) for its Room/Payment REST clients.

| Service | Main classes |
|---|---|
| auth-service | `AuthController`, `UserAdminController`, `AuthServiceImpl`, `JwtTokenProvider`, `AdminInitializer`, `SecurityConfig` |
| room-service | `RoomController`, `HotelController`, `PricingRuleController`, `InternalHoldController`, `InventoryServiceImpl`, `PricingServiceImpl`, `PriceCalculator`, `RoomSpecifications` |
| booking-service | `BookingController`, `BookingServiceImpl`, `RoomServiceClient`, `PaymentServiceClient`, `DownstreamCallExecutor`, `RestClientConfig` |
| payment-service | `PaymentController`, `InternalPaymentController`, `PaymentServiceImpl`, `SimulatedPaymentGateway` |
| api-gateway | `GatewaySecurityConfig`, `GatewaySecurityErrorHandler`, `RequestTracingFilter`, routes in `application.properties` |
| eureka-server | `EurekaServerApplication` (`@EnableEurekaServer`) |

## 5. Modularity principles applied

| Principle | How it shows in the code |
|---|---|
| Single responsibility | Each service maps to exactly one bounded context above. |
| Database per service | Four databases; each `application.properties` points to one. No service has another's JDBC URL. |
| No shared domain library | Each service has its own DTOs for the contracts it uses (e.g. `HoldResult` in Booking mirrors `HoldResponse` in Room). Services can evolve and deploy independently; the trade-off is a little duplicated security/error code, accepted for independence and STS simplicity. |
| Loose coupling | Only HTTP/JSON contracts; enum values crossing services are treated as strings in clients. |
| Location transparency | Callers use `lb://ROOM-SERVICE` / `http://ROOM-SERVICE`; physical host:port come from Eureka. |
| Independent deployability | Six independent Maven projects (`pom.xml` each), no parent aggregator required. |
| Statelessness | JWT instead of HTTP sessions → any instance can serve any request. |
| Explicit internal API | Service-to-service endpoints under `/internal/**`, protected by JWT + internal API key, never routed publicly. |

## 6. Cross-service references

| Column | In database | Refers to | Enforced by |
|---|---|---|---|
| `room_holds.user_id` | room | `users.id` | JWT subject (trusted signed claim) |
| `bookings.user_id` | booking | `users.id` | JWT subject |
| `bookings.room_id`, `hotel_id`, `hold_id` | booking | room DB rows | returned by Room Service hold call |
| `bookings.payment_id` | booking | `payments.id` | returned by Payment Service |
| `payments.booking_id`, `user_id` | payment | booking / auth | request from Booking Service + JWT |

These are plain `bigint` columns – **no SQL foreign keys across databases.**
