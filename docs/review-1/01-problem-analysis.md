# 01 – Problem Analysis

## 1. Organisation and context

**GrandVista Hospitality** (fictional) operates multiple hotel properties across South India – Hyderabad,
Vijayawada, Bengaluru and Chennai. Each property has several room categories (Standard, Deluxe, Suite) and sells
rooms online and at the front desk. The company wants a single digital platform that coordinates room
inventory, pricing, reservations and payments for all properties.

## 2. Current problems

| # | Problem | Consequence for GrandVista |
|---|---|---|
| P1 | **Reservation collisions** – two guests can reserve the same room for overlapping nights when availability is checked and updated in separate, uncoordinated steps. | Guests arrive to find their room given away; compensation cost and reputation damage. |
| P2 | **Overbooking** – availability is tracked as a simple "room free / room taken" flag instead of per date. | A room booked for 20–23 September appears unavailable for October too, or – worse – appears available for 22 September. Both lose revenue or cause collisions. |
| P3 | **Fragmented inventory** – every property keeps its own spreadsheet/system. | No company-wide view of occupancy; head office cannot compare or manage properties centrally. |
| P4 | **Static pricing** – one fixed tariff per room all year. | Money is left on the table during festivals (Dasara, Diwali, year-end) and when a hotel is nearly full; admins must edit every room manually. |
| P5 | **Unreliable payment workflow** – a booking is recorded before payment is known; failed payments leave rooms blocked. | "Ghost" reservations block sellable rooms; paid bookings may not be confirmed. |
| P6 | **Peak-traffic fragility** – festival and holiday seasons create short bursts of heavy demand on one monolithic system. | The whole system slows down or fails when only search traffic spikes. |
| P7 | **Weak access control** – shared staff logins, no separation between guests and administrators. | Guests could reach admin functions; no accountability for price changes. |

## 3. Root-cause analysis

* **P1 + P2** come from modelling availability as an attribute of a room rather than as *reservations over date
  ranges*, and from checking and writing availability without concurrency control.
* **P3** comes from lack of a shared service that owns inventory for all hotels.
* **P4** comes from hard-coded prices with no rule engine.
* **P5** comes from treating booking and payment as one step without explicit states or compensation.
* **P6** comes from a monolith that cannot scale its busiest part (room search) independently.
* **P7** comes from missing authentication/authorization design.

## 4. Stakeholders and users

| Actor | Goals | Role in system |
|---|---|---|
| **Guest (customer)** | Find a room for specific dates, see a fair price, book and pay quickly, view/cancel bookings. | `ROLE_USER` – self-registers. |
| **Administrator (GrandVista staff)** | Manage hotels and rooms, set base prices and pricing rules, see occupancy and all bookings, promote staff accounts. | `ROLE_ADMIN` – bootstrapped securely, never self-registered. |
| **Visitor (anonymous)** | Browse hotels, rooms, availability and prices before signing up. | No login; read-only public endpoints. |
| Platform services | Discover and call each other reliably. | Eureka-registered microservices. |

## 5. Proposed solution

A **microservices platform** built with Spring Boot and Spring Cloud, fronted by a React single-page application:

| Problem | Solution element |
|---|---|
| P1 Collisions | Room Service creates a date-range **hold** inside a transaction that first takes a **pessimistic row lock** (`SELECT … FOR UPDATE`) on the room; overlap is re-checked while the lock is held. A multi-threaded test proves only one of 8 simultaneous requests succeeds. |
| P2 Overbooking | Availability is **derived from `room_holds` rows** using the overlap rule `existing.checkIn < requested.checkOut AND existing.checkOut > requested.checkIn` (check-out exclusive). There is no boolean availability flag. |
| P3 Fragmented inventory | One **Room Service** owns hotels, rooms, holds and pricing for all properties; admin dashboard shows per-hotel occupancy. |
| P4 Static pricing | **Rule-based dynamic pricing**: `nightly price = base × seasonal multiplier × demand multiplier`; rules are data managed by admins. |
| P5 Payment workflow | **Booking → Payment → Room** orchestration with explicit states (PENDING/CONFIRMED/CANCELLED), hold expiry and compensating actions (release hold, refund). |
| P6 Peak traffic | Stateless services registered in **Eureka**, **API Gateway** + **Spring Cloud LoadBalancer**; e.g. extra Room Service instances can be started on other ports without code changes. |
| P7 Access control | **JWT** (HS256, expiry, issuer, roles) issued by Auth Service; validated at the gateway **and** in every service; BCrypt password hashing; admin-only operations enforced server-side. |

## 6. Scope

**In scope (Review-1 implementation)**

* User registration, login, JWT, roles `ROLE_USER` / `ROLE_ADMIN`, admin bootstrap and promotion.
* Hotel and room management, room status (active / maintenance / inactive), base price management.
* Date-aware availability, room search with date filter, collision prevention.
* Seasonal and occupancy-based dynamic pricing with transparent breakdown.
* Booking lifecycle: create (hold), pay, confirm, cancel, automatic expiry.
* Simulated payment processing, refunds on cancellation/compensation.
* Service discovery (Eureka), API Gateway routing/security/CORS, client-side load balancing.
* React UI for guests and admins; Postman collection; technical documentation.

**Out of scope (explicitly not implemented)**

* Real payment gateway or storage of card/bank data.
* Email/SMS notifications, loyalty programmes, reviews.
* Message brokers (Kafka), caches (Redis), Kubernetes/cloud deployment, ML pricing.
* Refresh tokens, multi-factor authentication, rate limiting, centralised configuration server.

## 7. Assumptions

1. All properties are in India; prices are in **INR**; dates use the hotel-stay convention (check-in date inclusive,
   check-out date exclusive).
2. A booking is for one room; a guest may make several bookings.
3. Payment is simulated and returns immediately (SUCCESS or FAILED) – no asynchronous bank callbacks.
4. A reservation hold lasts **15 minutes** (configurable `HOLD_MINUTES`); unpaid holds stop blocking the room
   automatically afterwards.
5. Maximum stay is 30 nights per booking; maximum 10 guests per room.
6. Administrators do not make guest bookings themselves.
7. One PostgreSQL server hosts all databases in development; in production each could be separated.

## 8. Constraints

| Type | Constraint |
|---|---|
| Technology | Java 25 (installed JDK), Maven, Spring Boot 4.0.8, Spring Cloud 2025.1.3, PostgreSQL 18, React + Vite. |
| Tooling | Each backend service must be an independent Maven project importable into **Spring Tool Suite**; databases inspected with **pgAdmin4**. |
| Architecture | Database-per-service; no service reads another service's database; no cross-database foreign keys. |
| Security | No secrets in source code; passwords hashed; JWT secret supplied via configuration. |
| Academic | Must be explainable in a viva; no over-engineering; payments must be mock only. |
| Environment | Local Windows development machine; antivirus TLS inspection requires a JVM trust-store flag for Maven downloads (documented in README). |
