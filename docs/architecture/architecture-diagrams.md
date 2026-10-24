# GrandVista – Architecture Diagrams

All diagrams are Mermaid and render on GitHub, in VS Code (Markdown Preview Mermaid Support) or at
<https://mermaid.live>.

---

## A. System architecture

```mermaid
flowchart TB
    subgraph Client["Client tier"]
        UI["React SPA (Vite)<br/>localhost:5173"]
        PM["Postman"]
    end

    subgraph Edge["Edge tier"]
        GW["API-GATEWAY :8080<br/>Spring Cloud Gateway (WebFlux)<br/>routing · JWT pre-check · CORS · X-Request-Id"]
    end

    subgraph Discovery["Service discovery"]
        EU["EUREKA-SERVER :9000<br/>service registry + dashboard"]
    end

    subgraph Services["Business microservices (Spring Boot MVC, stateless)"]
        AU["AUTH-SERVICE :8081<br/>register · login · JWT issuing"]
        RO["ROOM-SERVICE :8082<br/>hotels · rooms · holds · pricing"]
        BO["BOOKING-SERVICE :8083<br/>booking lifecycle · saga orchestration"]
        PA["PAYMENT-SERVICE :8084<br/>simulated payments · refunds"]
    end

    subgraph Data["PostgreSQL server (one instance, database-per-service)"]
        DB1[("grandvista_auth_db")]
        DB2[("grandvista_room_db")]
        DB3[("grandvista_booking_db")]
        DB4[("grandvista_payment_db")]
    end

    UI -- "HTTPS/JSON + Bearer JWT" --> GW
    PM --> GW
    GW -- "lb://AUTH-SERVICE" --> AU
    GW -- "lb://ROOM-SERVICE" --> RO
    GW -- "lb://BOOKING-SERVICE" --> BO
    GW -- "lb://PAYMENT-SERVICE" --> PA

    BO -- "load-balanced RestClient<br/>http://ROOM-SERVICE" --> RO
    BO -- "load-balanced RestClient<br/>http://PAYMENT-SERVICE" --> PA

    AU --> DB1
    RO --> DB2
    BO --> DB3
    PA --> DB4

    GW -. "register + fetch registry" .-> EU
    AU -. register .-> EU
    RO -. register .-> EU
    BO -. "register + fetch registry" .-> EU
    PA -. register .-> EU
```

---

## B. Service communication

```mermaid
flowchart LR
    subgraph External["External API (through gateway, /api/**)"]
        direction TB
        E1["/api/auth/**"]
        E2["/api/rooms/** · /api/hotels/** · /api/pricing-rules/**"]
        E3["/api/bookings/**"]
        E4["/api/payments/** (read-only)"]
    end

    GW((API Gateway))
    E1 & E2 & E3 & E4 --> GW

    GW -->|StripPrefix=1| AU[AUTH-SERVICE]
    GW -->|StripPrefix=1 + Retry GET| RO[ROOM-SERVICE]
    GW -->|StripPrefix=1| BO[BOOKING-SERVICE]
    GW -->|StripPrefix=1| PA[PAYMENT-SERVICE]

    subgraph Internal["Internal API (/internal/**, NOT routed by the gateway)"]
        direction TB
        I1["POST /internal/holds<br/>POST /internal/holds/{id}/confirm<br/>POST /internal/holds/{id}/release"]
        I2["POST /internal/payments<br/>POST /internal/payments/{id}/refund"]
    end

    BO -->|"Authorization: Bearer (user JWT)<br/>X-Internal-Api-Key"| I1
    BO -->|"Authorization: Bearer (user JWT)<br/>X-Internal-Api-Key"| I2
    I1 --- RO
    I2 --- PA
```

**Communication style:** synchronous REST/JSON. Service locations are never hard-coded: the gateway uses
`lb://SERVICE-NAME` URIs and Booking Service uses a load-balanced `RestClient` with `http://SERVICE-NAME`
base URLs. Spring Cloud LoadBalancer resolves these names against the Eureka registry (round-robin).

---

## C. Booking sequence (Booking → Payment → Room update)

```mermaid
sequenceDiagram
    autonumber
    actor G as Guest (React)
    participant GW as API Gateway
    participant BS as Booking Service
    participant RS as Room Service
    participant PS as Payment Service

    Note over G,PS: Step 1 - reserve
    G->>GW: POST /api/bookings {roomId, checkIn, checkOut, guests} + JWT
    GW->>GW: validate JWT, require ROLE_USER
    GW->>BS: POST /bookings
    BS->>BS: validate dates (not past, checkOut > checkIn, ≤ 30 nights)
    BS->>RS: POST /internal/holds (JWT + internal key)
    RS->>RS: SELECT room FOR UPDATE (row lock)
    RS->>RS: overlap check: existing.checkIn < new.checkOut AND existing.checkOut > new.checkIn
    alt dates already reserved
        RS-->>BS: 409 Conflict
        BS-->>G: 409 "Room … already reserved"
    else available
        RS->>RS: price = Σ base × seasonal × demand
        RS->>RS: INSERT room_holds (HELD, expires in 15 min)
        RS-->>BS: 201 hold {holdId, totalAmount, expiresAt}
        BS->>BS: INSERT bookings (PENDING)
        BS-->>G: 201 booking PENDING (paymentDueBy)
    end

    Note over G,PS: Step 2 - pay
    G->>GW: POST /api/bookings/{id}/pay {paymentMethod}
    GW->>BS: POST /bookings/{id}/pay
    BS->>BS: short transaction: PENDING and not expired → PAYMENT_IN_PROGRESS (committed, no lock kept)
    BS->>PS: POST /internal/payments {bookingId, amount} (idempotent per booking)
    PS->>PS: simulated gateway decision, INSERT payments (unique success per booking)
    alt payment FAILED
        PS-->>BS: status FAILED
        BS->>RS: POST /internal/holds/{holdId}/release
        BS->>BS: booking → CANCELLED
        BS-->>G: outcome FAILED, room released
    else payment SUCCESS
        PS-->>BS: status SUCCESS + transactionReference
        BS->>RS: POST /internal/holds/{holdId}/confirm
        alt confirm OK
            RS-->>BS: hold CONFIRMED
            BS->>BS: booking → CONFIRMED
            BS-->>G: outcome SUCCESS, booking CONFIRMED
        else confirm failed (hold expired / Room Service down)
            BS->>BS: booking → CANCELLATION_PENDING (committed)
            BS->>PS: POST /internal/payments/{id}/refund (compensation, FIRST)
            BS->>RS: POST /internal/holds/{holdId}/release (SECOND)
            BS->>BS: booking → CANCELLED "payment refunded"
            BS-->>G: outcome - not confirmed, refunded (or "refund in progress" – retried by the recovery job)
        end
    else no answer (timeout / outage)
        BS->>PS: GET /internal/payments/booking/{id}
        BS-->>G: 202 UNKNOWN - booking stays PAYMENT_IN_PROGRESS, recovery job settles it later
    end
```

---

## D. Authentication flow (JWT)

```mermaid
sequenceDiagram
    autonumber
    actor U as User (React)
    participant GW as API Gateway
    participant AS as Auth Service
    participant DB as grandvista_auth_db
    participant MS as Any business service

    U->>GW: POST /api/auth/login {email, password}
    GW->>AS: POST /auth/login (public route)
    AS->>DB: SELECT user BY email
    AS->>AS: BCrypt.matches(password, password_hash)
    alt invalid credentials
        AS-->>U: 401 "Invalid email or password"
    else valid
        AS->>AS: sign JWT HS256 {iss, sub=userId, roles, email, name, iat, exp, jti}
        AS-->>U: 200 {accessToken, tokenType: Bearer, expiresAt, user}
    end

    U->>U: store token, show role-based navigation
    U->>GW: GET /api/bookings/my  Authorization: Bearer <JWT>
    GW->>GW: verify signature + exp + iss, map roles → authorities
    alt missing / expired / tampered token
        GW-->>U: 401 JSON (WWW-Authenticate: Bearer)
    else role not allowed
        GW-->>U: 403 JSON
    else allowed
        GW->>MS: forward request (Authorization header kept)
        MS->>MS: verify JWT AGAIN + enforce role and ownership
        MS-->>U: 200 data
    end
```

---

## E. Database ownership

```mermaid
flowchart LR
    AU[AUTH-SERVICE] ==> A[("grandvista_auth_db<br/>users")]
    RO[ROOM-SERVICE] ==> R[("grandvista_room_db<br/>hotels · rooms<br/>room_holds · pricing_rules")]
    BO[BOOKING-SERVICE] ==> B[("grandvista_booking_db<br/>bookings")]
    PA[PAYMENT-SERVICE] ==> P[("grandvista_payment_db<br/>payments")]

    BO -. "REST only<br/>(roomId, holdId)" .-> RO
    BO -. "REST only<br/>(paymentId)" .-> PA

    classDef db fill:#fdf6e3,stroke:#c9a227,color:#14213d;
    class A,R,B,P db;
```

Solid arrows: the only database connection each service has. Dotted arrows: cross-service references are **plain
ID columns** resolved through REST calls; there are no cross-database foreign keys.

---

## F. Entity relationships per database

```mermaid
erDiagram
    HOTELS ||--o{ ROOMS : "has (FK hotel_id)"
    ROOMS ||--o{ ROOM_HOLDS : "reserved by (FK room_id)"
    HOTELS {
        bigint id PK
        varchar name
        varchar city
        int star_rating
        boolean active
    }
    ROOMS {
        bigint id PK
        bigint hotel_id FK
        varchar room_number
        varchar room_type
        numeric base_price
        int capacity
        varchar status
    }
    ROOM_HOLDS {
        bigint id PK
        bigint room_id FK
        bigint user_id "auth id, no FK"
        date check_in
        date check_out
        varchar status
        timestamptz expires_at
        numeric total_amount
    }
    PRICING_RULES {
        bigint id PK
        varchar rule_type
        numeric multiplier
        date start_date
        date end_date
        int min_occupancy_percent
        bigint hotel_id "optional scope"
    }
```
