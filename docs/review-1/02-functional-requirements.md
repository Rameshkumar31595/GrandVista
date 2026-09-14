# 02 – Functional Requirements

Priority: **M** = must (implemented), **S** = should (implemented). Every requirement below is implemented; the
*Implementation* column points to the code that satisfies it.

## Identity and access

| ID | Requirement | Actor | Pri | Acceptance criteria | Implementation |
|---|---|---|---|---|---|
| FR-01 | **User registration** | Visitor | M | Name, valid unique email and password (8–72 chars, letter + digit) required; password stored as BCrypt hash; account always `ROLE_USER`; duplicate email → 409. | `auth-service` `AuthController#register`, `AuthServiceImpl#register` |
| FR-02 | **User authentication** | User/Admin | M | Valid credentials → signed JWT with expiry and role; wrong email or password → identical 401 message. | `AuthServiceImpl#login`, `JwtTokenProvider` |
| FR-03 | **Current profile** | User/Admin | S | `GET /auth/me` returns the account of the token's subject. | `AuthController#me` |
| FR-04 | **Administrator bootstrap** | System | M | On start, if no admin exists and `ADMIN_PASSWORD` is configured, create one admin; never via public registration. | `AdminInitializer` |
| FR-05 | **Role management** | Admin | S | Admin lists users and promotes/demotes others; cannot change own role. | `UserAdminController`, `UserManagementServiceImpl` |
| FR-06 | **Role-based authorization** | System | M | Missing/invalid/expired token → 401; valid token without required role → 403; enforced at gateway and in each service. | `GatewaySecurityConfig`, each `SecurityConfig` |

## Catalogue and inventory

| ID | Requirement | Actor | Pri | Acceptance criteria | Implementation |
|---|---|---|---|---|---|
| FR-07 | **Hotel management** | Admin | M | Create/update hotel with validated name, city, state, country, address, 1–5 stars; name+city unique. | `HotelController`, `HotelServiceImpl` |
| FR-08 | **Room management** | Admin | M | Create/update room (hotel, number, type, base price > 0, capacity 1–10); room number unique per hotel. | `RoomController`, `RoomServiceImpl` |
| FR-09 | **Room status / soft delete** | Admin | M | Set ACTIVE / MAINTENANCE / INACTIVE; delete = INACTIVE so historic bookings stay valid; non-active rooms cannot be booked. | `RoomServiceImpl#updateStatus`, `#deactivateRoom` |
| FR-10 | **Room search** | Visitor | M | Filter by city, hotel, type, guests, max price; with check-in/check-out only rooms free for the whole stay are returned. | `RoomSpecifications#bookableMatching` |
| FR-11 | **Room details** | Visitor | M | Room, hotel, type, capacity, base price, status. | `RoomController#getRoom` |
| FR-12 | **Date-aware availability check** | Visitor | M | Returns available/unavailable with reason for a date range; validates dates. | `InventoryServiceImpl#checkAvailability` |
| FR-13 | **Inventory overview** | Admin | S | Per-hotel total/active/maintenance rooms, rooms reserved tonight, occupancy %; per-room upcoming reservations. | `InventoryServiceImpl#inventorySummary`, `#upcomingHoldsForRoom` |

## Pricing

| ID | Requirement | Actor | Pri | Acceptance criteria | Implementation |
|---|---|---|---|---|---|
| FR-14 | **Base price management** | Admin | M | Change a room's nightly base price (positive, ≤ 2 decimals). | `RoomController#updatePrice` |
| FR-15 | **Dynamic pricing** | System | M | Nightly price = base × highest matching SEASONAL multiplier for that night × highest matching OCCUPANCY multiplier for the hotel; response shows base, adjustment, rules applied and final total. | `PriceCalculator`, `PricingServiceImpl` |
| FR-16 | **Pricing rule management** | Admin | M | CRUD of SEASONAL (date range) and OCCUPANCY (threshold %) rules with multiplier 0.50–3.00, optional hotel/room-type scope, enable/disable. | `PricingRuleController`, `PricingRuleServiceImpl` |

## Booking and payment

| ID | Requirement | Actor | Pri | Acceptance criteria | Implementation |
|---|---|---|---|---|---|
| FR-17 | **Booking creation** | User | M | Validates dates (not past, check-out after check-in, ≤ 30 nights) and guests; Room Service verifies room, capacity, availability and price; booking stored PENDING with payment deadline. Client never supplies the price. | `BookingServiceImpl#createBooking`, `InventoryServiceImpl#createHold` |
| FR-18 | **Collision prevention** | System | M | Overlapping stays for the same room are rejected with 409 (check-out day is free for a new check-in); concurrent requests for the same room/dates yield exactly one success. | `RoomHoldRepository#countBlockingHolds`, `RoomRepository#findByIdForUpdate`, `ConcurrentHoldIntegrationTest` |
| FR-19 | **Payment processing (simulated)** | User | M | Pay a PENDING booking with a method type; mock gateway returns SUCCESS/FAILED; transaction stored with reference; no card data accepted; a booking cannot be paid twice. | `PaymentServiceImpl#processPayment`, `SimulatedPaymentGateway` |
| FR-20 | **Booking confirmation workflow** | System | M | Payment SUCCESS → hold CONFIRMED → booking CONFIRMED. Payment FAILED → hold released → booking CANCELLED. Confirmation failure after payment → refund → booking CANCELLED. | `BookingServiceImpl#payForBooking` |
| FR-21 | **Hold expiry** | System | M | Unpaid holds stop blocking after 15 minutes; expired pending bookings are cancelled automatically. | `RoomHold#isExpired`, `HoldExpiryScheduler`, `PendingBookingExpiryScheduler` |
| FR-22 | **Booking cancellation** | User/Admin | M | Owner or admin cancels PENDING (release) or CONFIRMED before check-in (release + refund). | `BookingServiceImpl#cancelBooking` |
| FR-23 | **My bookings / payments** | User | M | User sees only own bookings and payments; accessing another user's record → 403. | `BookingController#myBookings`, `PaymentController#myPayments` |
| FR-24 | **Administrative booking view** | Admin | S | List all bookings (filter by status) and all payments. | `BookingController#allBookings`, `PaymentController#allPayments` |

## Platform

| ID | Requirement | Actor | Pri | Acceptance criteria | Implementation |
|---|---|---|---|---|---|
| FR-25 | **Service discovery** | System | M | All services register with Eureka under logical names; dashboard lists them. | `eureka-server`, `eureka.client.*` config |
| FR-26 | **API Gateway routing** | System | M | Single entry `:8080/api/**` routes to services by Eureka name; internal endpoints not exposed. | `api-gateway/application.properties` |
| FR-27 | **Load-balanced inter-service calls** | System | M | Booking Service calls Room/Payment Service by service name with round-robin across instances. | `RestClientConfig` (LoadBalancer interceptor) |
| FR-28 | **Consistent error responses** | System | M | All errors return `{timestamp, status, error, message, path}` (+ `validationErrors`), no stack traces. | `GlobalExceptionHandler`, `SecurityErrorWriter` |
| FR-29 | **API documentation** | Developer | S | Swagger UI per business service. | springdoc `/swagger-ui.html` |
| FR-30 | **Web client** | User/Admin | M | React UI with role-aware navigation, guest booking/payment flow and admin management pages, using only the gateway. | `grandvista-frontend` |
