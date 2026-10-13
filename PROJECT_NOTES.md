# PayFlux — PayPal Clone: Complete Project Study Notes

> **Purpose:** This file is your single reference to understand the entire project end-to-end.
> Read it top-to-bottom once. After that, use the sections as needed when revising specific areas.

---

## Table of Contents

1. [What Is This Project?](#1-what-is-this-project)
2. [Architecture Overview (The Big Picture)](#2-architecture-overview-the-big-picture)
3. [Service Port Map & Infrastructure](#3-service-port-map--infrastructure)
4. [API Gateway Service](#4-api-gateway-service)
5. [User Service](#5-user-service)
6. [Wallet Service](#6-wallet-service)
7. [Transaction Service](#7-transaction-service)
8. [Notification Service](#8-notification-service)
9. [Reward Service](#9-reward-service)
10. [Kafka: The Messaging Bus](#10-kafka-the-messaging-bus)
11. [Key Design Patterns Used](#11-key-design-patterns-used)
12. [Complete Money Transfer Flow (Step-by-Step)](#12-complete-money-transfer-flow-step-by-step)
13. [How Services Talk To Each Other](#13-how-services-talk-to-each-other)
14. [Database Tables](#14-database-tables)
15. [How JWTs Work In This Project](#15-how-jwts-work-in-this-project)
16. [Rate Limiting With Redis](#16-rate-limiting-with-redis)
17. [Environment Setup & Running Locally](#17-environment-setup--running-locally)

---

## 1. What Is This Project?

**PayFlux** is a PayPal-inspired **backend system** built with **Java Spring Boot** using a **microservices architecture**. It is NOT a monolith — it is split into multiple small, independent services that each do one thing.

The core use case:
- A user can **sign up** and get a **wallet** automatically.
- A user can **send money** to another user.
- Money is transferred safely using a **Hold → Credit → Capture** pattern (like PayPal does internally).
- After a transfer, both sender and receiver get a **notification**.
- The sender earns **reward points**.

---

## 2. Architecture Overview (The Big Picture)

```
                        ┌─────────────────────────────────┐
                        │          CLIENT (Postman/App)    │
                        └────────────────┬────────────────┘
                                         │ HTTP Request
                                         ▼
                        ┌────────────────────────────────────┐
                        │        API Gateway  :8080           │
                        │  - JWT Auth Filter (GlobalFilter)   │
                        │  - Rate Limiting (Redis)            │
                        │  - Route to correct service         │
                        └──┬───┬────┬──────┬────────┬────────┘
                           │   │    │      │        │
               /auth/**    │   │    │      │        │  /api/v1/wallets/**
               /api/users/**│  │    │      │        └──────► Wallet Service :8083
                           │   │    │      │
                           ▼   │    │      └──► /api/notifications/** → Notification :8084
                    User       │    │
                  Service      │    └──► /api/rewards/** → Reward Service :8089
                   :8081       │
                               └──► /api/transactions/** → Transaction Service :8082


                    Transaction Service
                          │
                          │  REST calls (RestTemplate + Retry)
                          ▼
                    Wallet Service
                    (Hold, Credit, Capture, Release)
                          │
                          │  Kafka event published after SUCCESS
                          ▼
                    Topic: "txn-initiated"
                    ┌──────┴───────┐
                    ▼              ▼
            Notification       Reward
              Service          Service
          (group: notification-group)  (group: reward-group)
```

**Key insight:** The API Gateway is the ONLY entry point for all external traffic. Every request goes through it first. The individual services never talk to the outside world directly.

---

## 3. Service Port Map & Infrastructure

| Service              | Port  | Database           | Communication     |
|----------------------|-------|--------------------|-------------------|
| API Gateway          | 8080  | None               | Routes HTTP       |
| User Service         | 8081  | PostgreSQL         | Feign → Wallet    |
| Transaction Service  | 8082  | PostgreSQL         | REST → Wallet, Kafka Producer |
| Wallet Service       | 8083  | PostgreSQL         | None outbound     |
| Notification Service | 8084  | PostgreSQL         | Kafka Consumer    |
| Reward Service       | 8089  | PostgreSQL         | Kafka Consumer    |

**Infrastructure (via Docker Compose):**
- **Kafka** on port `9092` — message broker
- **Zookeeper** on port `2181` — Kafka coordinator
- **Redis** on port `6379` — used by API Gateway for rate limiting

> **PostgreSQL** is run separately (not in docker-compose). Connection details are provided via `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` environment variables in `set-env.bat`.

---

## 4. API Gateway Service

**Package:** `com.payflux.api_gateway`  
**Port:** `8080`  
**Framework:** Spring Cloud Gateway (reactive, non-blocking WebFlux-based)

This is the **front door** of the entire system. Every single HTTP request from a client hits here first.

### 4.1 What It Does

1. **Routing** — Reads the URL path and decides which downstream service to send the request to.
2. **JWT Authentication** — Validates the Bearer token on every request BEFORE it reaches any service.
3. **Rate Limiting** — Limits how many requests a user/IP can make per second (uses Redis as the counter store).

### 4.2 Routes (defined in `application.yml`)

| Route ID             | URL Pattern              | Forwards To          |
|----------------------|--------------------------|----------------------|
| `user-service-auth`  | `/auth/**`               | localhost:8081       |
| `user-service-api`   | `/api/users/**`          | localhost:8081       |
| `transaction-service`| `/api/transactions/**`   | localhost:8082       |
| `wallet-service`     | `/api/v1/wallets/**`     | localhost:8083       |
| `notification-service`| `/api/notifications/**` | localhost:8084       |
| `reward-service`     | `/api/rewards/**`        | localhost:8089       |

### 4.3 JWT Auth Filter (`JwtAuthFilter.java`)

This is a **GlobalFilter** — it runs on EVERY request, before the request is forwarded.

```
Order = -100 (very high priority, runs first)
```

**How it works step by step:**

1. Get the request path (e.g., `/api/transactions/create`)
2. Check if the path starts with a "public" prefix:
   - Currently ALL paths are listed as public (auth, users, transactions, wallets, notifications, rewards)
   - This means JWT is NOT enforced on any path right now (the filter is ready but soft-enforced)
3. If NOT public: look for `Authorization: Bearer <token>` header
4. If token exists and is valid → extract claims from JWT:
   - `sub` (email) → forwarded as `X-User-Email` header
   - `userId` → forwarded as `X-User-Id` header
   - `role` → forwarded as `X-User-Role` header
5. These headers are **injected into the forwarded request** so downstream services can read them without doing JWT validation themselves.

**Why this is powerful:** No downstream service needs to validate JWTs. The gateway does it once centrally and passes user identity as trusted headers.

### 4.4 Rate Limiting (`RateLimitConfig.java`)

Uses **Redis** to count requests. Every route has these limits:
- `replenishRate: 10` — 10 tokens added per second
- `burstCapacity: 20` — max 20 requests in a burst
- `requestedTokens: 1` — each request costs 1 token

The key used to identify who is making the request:
- If `X-User-Id` header is present → rate limit per user ID
- Otherwise → rate limit per IP address
- Fallback → "anonymous"

### 4.5 JWT Util (`JWTUtil.java`)

Simple class with one job: **validate a JWT token and return its claims**.

```java
Claims claims = JWTUtil.validateToken(token);
// Claims has: subject (email), userId, role
```

Uses HMAC-SHA (symmetric key). The same secret is used in both the Gateway and the User Service to sign/verify tokens.

---

## 5. User Service

**Package:** `com.payflux.user_service`  
**Port:** `8081`  
**Database:** PostgreSQL, table `app_user`

Handles **registration, login, and user lookups**. It is also responsible for **creating a wallet** for every new user.

### 5.1 Entities

**`User` entity** → table `app_user`:
| Field    | Type   | Notes                        |
|----------|--------|------------------------------|
| id       | Long   | Auto-generated primary key   |
| name     | String |                              |
| email    | String | Unique (no duplicate emails) |
| password | String | BCrypt hashed                |
| role     | String | Always `"ROLE_USER"` on signup |

### 5.2 Auth Controller (`/auth/**`)

These routes are public — no JWT required.

#### `POST /auth/signup`
**Request body:**
```json
{ "name": "Raj", "email": "raj@gmail.com", "password": "pass123" }
```
**What happens:**
1. Check if email already exists → return `400 Bad Request` if so.
2. Create a `User` object with BCrypt-encoded password and role `ROLE_USER`.
3. Call `userService.createUser(user)`.
   - Inside `createUser`: save to DB first, then call Wallet Service via Feign to create a wallet.
   - If wallet creation fails → **delete the user from DB** and throw exception (manual rollback).
4. Return `200 OK` with success message.

**The Feign call to Wallet Service:**
```java
// WalletClient.java
@FeignClient(name = "wallet-service", url = "http://localhost:8083/api/v1/wallets")
walletClient.createWallet(new CreateWalletRequest(userId, "INR"));
```

#### `POST /auth/login`
**Request body:**
```json
{ "email": "raj@gmail.com", "password": "pass123" }
```
**What happens:**
1. Find user by email → return `401` if not found.
2. Check password with BCrypt `matches()` → return `401` if wrong.
3. Build a JWT with claims: `{ role, userId }` and subject = email.
4. Return `{ "token": "eyJ..." }`.

**JWT is signed with** `JWTUtil.generateToken()` using the same HMAC key as the Gateway.

### 5.3 User Controller (`/api/users/**`)

| Method | URL                  | What It Does                  |
|--------|----------------------|-------------------------------|
| POST   | `/api/users`         | Create a user (raw, no wallet)|
| GET    | `/api/users/{id}`    | Get user by ID                |
| GET    | `/api/users/all`     | Get all users                 |

> **Note:** The raw `POST /api/users` does NOT auto-create a wallet. Only the `/auth/signup` flow does the full registration with wallet.

### 5.4 Security Config

The service has its own Spring Security config that allows all requests (since JWT is already validated by the Gateway). The password encoder bean (BCrypt) is defined here.

---

## 6. Wallet Service

**Package:** `com.payflux.wallet_service`  
**Port:** `8083`  
**Database:** PostgreSQL

This is the **heart of the financial operations**. It manages wallet balances with a highly safe **two-balance model** and a **Hold-Capture** mechanism to prevent double-spending.

### 6.1 The Two-Balance Model

Every wallet has **two balance fields:**

| Field              | Meaning                                              |
|--------------------|------------------------------------------------------|
| `balance`          | The **real, settled** balance (actual money)         |
| `availableBalance` | Balance minus any **active holds** (money you can spend) |

**Why?** When you initiate a transfer, money is "reserved" (a hold is placed) before the transaction completes. This prevents:
- Spending money twice at the same time
- Showing incorrect balance to the user

**Example:**
- Balance = ₹1000, AvailableBalance = ₹1000
- You send ₹300 → Hold placed: AvailableBalance becomes ₹700, Balance stays ₹1000
- Transaction completes → Capture: Balance becomes ₹700, AvailableBalance stays ₹700

### 6.2 Entities

**`Wallet` entity** → table `wallets`:
| Field             | Type    | Notes                     |
|-------------------|---------|---------------------------|
| id                | Long    | Auto-generated            |
| userId            | Long    | Links to User Service user|
| currency          | String  | e.g., "INR"               |
| balance           | Long    | Settled balance           |
| availableBalance  | Long    | Balance minus holds       |

**`WalletHold` entity** → table `wallet_holds`:
| Field         | Type          | Notes                                          |
|---------------|---------------|------------------------------------------------|
| id            | Long          | Auto-generated                                 |
| wallet        | Wallet (FK)   | Which wallet this hold is on                   |
| holdReference | String        | Unique ID like `"HOLD-1712345678901"`           |
| amount        | Long          | Reserved amount                                |
| status        | String        | `ACTIVE`, `CAPTURED`, or `RELEASED`            |
| createdAt     | LocalDateTime |                                                |
| expiresAt     | LocalDateTime | Set to `now + 5 minutes` when hold is created  |

**`Transaction` entity (local)** → table `transactions` (wallet's own transaction log):
| Field    | Type   | Notes                           |
|----------|--------|---------------------------------|
| walletId | Long   |                                 |
| type     | String | `"CREDIT"` or `"DEBIT"`        |
| amount   | Long   |                                 |
| status   | String | `"SUCCESS"`                     |

**`IdempotencyRecord` entity** → table for tracking processed requests:
| Field          | Type   | Notes                                           |
|----------------|--------|-------------------------------------------------|
| idempotencyKey | String | Unique key provided by caller                   |
| operation      | String | `"CREDIT"`, `"DEBIT"`, `"HOLD"`                |
| responseJson   | String | The stored response (serialized as JSON string) |

### 6.3 API Endpoints

| Method | URL                              | What It Does                                |
|--------|----------------------------------|---------------------------------------------|
| POST   | `/api/v1/wallets`                | Create wallet for a user                    |
| POST   | `/api/v1/wallets/credit`         | Add money to a wallet                       |
| POST   | `/api/v1/wallets/debit`          | Remove money from a wallet                  |
| GET    | `/api/v1/wallets/{userId}`       | Get wallet details                          |
| POST   | `/api/v1/wallets/hold`           | Place a hold (reserve money)                |
| POST   | `/api/v1/wallets/capture`        | Capture a hold (finalise the debit)         |
| POST   | `/api/v1/wallets/release/{ref}`  | Release a hold (cancel reservation)         |

### 6.4 Idempotency (Extremely Important!)

Every write operation (credit, debit, hold) accepts an optional `idempotencyKey`.

**The problem it solves:** Network glitches can cause a client to retry a request (e.g., debit ₹500) not knowing if the first one succeeded. Without idempotency, the user gets debited twice.

**How it works:**
1. Request comes in with `idempotencyKey = "abc-123"`.
2. Check `idempotency_records` table: does `"abc-123"` exist?
3. **If YES** → return the **stored response** immediately. Do NOT process again.
4. **If NO** → process the operation normally, then **save the response JSON** into the table with that key.

This guarantees: **no matter how many times you send the same request with the same key, the operation happens exactly once.**

### 6.5 Hold → Capture → Release Lifecycle

```
                   availableBalance -= amount
                          │
              ┌───────────▼──────────┐
              │  HOLD placed (ACTIVE) │
              │  expiresAt = now+5min │
              └──────────┬──────────┘
                         │
              ┌──────────┴──────────┐
              │                     │
              ▼                     ▼
        CAPTURE                 RELEASE
     (success case)          (failure/cancel case)
    balance -= amount         availableBalance += amount
    status = CAPTURED         status = RELEASED
```

### 6.6 HoldExpiryScheduler

Runs every **60 seconds** (configurable via `wallet.hold.expiry.scan-rate-ms`).

**What it does:**
1. Query all `WalletHold` with `status = "ACTIVE"` AND `expiresAt < now`.
2. For each expired hold → call `walletService.releaseHold(ref)`.
3. This automatically restores `availableBalance` for stale/orphaned holds.

**Why this exists:** If Transaction Service places a hold but then crashes and never calls capture or release, the funds would be frozen forever. The scheduler prevents this.

---

## 7. Transaction Service

**Package:** `com.payflux.transaction_service`  
**Port:** `8082`  
**Database:** PostgreSQL

Orchestrates a **complete money transfer** between two users. It coordinates multiple calls to the Wallet Service and publishes events to Kafka.

### 7.1 Entity

**`Transaction` entity** → table `transaction`:
| Field          | Type          | Notes                                     |
|----------------|---------------|-------------------------------------------|
| id             | Long          | Auto-generated                            |
| senderId       | Long          | User ID of the sender                     |
| receiverId     | Long          | User ID of the receiver                   |
| amount         | Double        | Must be positive                          |
| timeStamp      | LocalDateTime | Set on creation                           |
| status         | String        | `PENDING`, `SUCCESS`, `FAILED`            |
| idempotencyKey | String        | Unique — prevents duplicate transactions  |
| holdReference  | String        | The HOLD ID from wallet service           |

### 7.2 API Endpoints

| Method | URL                        | What It Does                  |
|--------|----------------------------|-------------------------------|
| POST   | `/api/transactions/create` | Create and execute a transfer |
| GET    | `/api/transactions/all`    | List all transactions         |

### 7.3 The Transfer Logic — Step by Step

When `POST /api/transactions/create` is called:

**Input:**
```json
{ "senderId": 1, "receiverId": 2, "amount": 500.0, "idempotencyKey": "optional-uuid" }
```

**Step 0 — Idempotency check:**
- If `idempotencyKey` is null → generate a random UUID.
- Look for a transaction with this key in DB.
- If found → return it immediately (already processed).

**Step 1 — Save as PENDING:**
- Save transaction with `status = "PENDING"` and the idempotency key.

**Step 2 — Place hold on sender's wallet:**
```java
String holdReference = walletRestClient.holdWallet(senderId, amountInLong, "txn-{id}-hold");
```
- Calls `POST /api/v1/wallets/hold` on Wallet Service.
- This reserves the money — `availableBalance` goes DOWN, `balance` stays same.
- If this fails → set transaction to `FAILED`, throw exception.
- The idempotency key `"txn-{id}-hold"` ensures hold is placed only once even on retry.

**Step 3 — Credit receiver's wallet:**
```java
walletRestClient.creditWallet(receiverId, amountInLong, "txn-{id}-credit");
```
- Calls `POST /api/v1/wallets/credit` on Wallet Service.
- Receiver's balance and availableBalance both go UP.
- If this fails → **compensate** by calling `walletRestClient.releaseHold(holdReference)`.
  - This restores sender's `availableBalance`.
  - Then set transaction to `FAILED`.

**Step 4 — Capture the hold:**
```java
walletRestClient.captureHold(holdReference);
```
- Calls `POST /api/v1/wallets/capture`.
- This FINALIZES the debit: sender's `balance` goes DOWN to match `availableBalance`.
- If capture fails: **this is non-fatal** — the `HoldExpiryScheduler` will eventually auto-release the hold (the money effectively stays reserved until then). The system logs a WARNING.

**Step 5 — Mark SUCCESS and publish Kafka event:**
```java
saved.setStatus("SUCCESS");
kafkaEventProducer.sendTransactionEvent(String.valueOf(saved.getId()), saved);
```
- Kafka topic: `"txn-initiated"`
- The entire `Transaction` object is serialized as JSON and published.

### 7.4 WalletRestClient — How It Calls Wallet Service

Uses **`RestTemplate`** with **Spring Retry** (`@Retryable`):
- Retries up to **3 times** on `ResourceAccessException` (network errors).
- Backoff: starts at 500ms, multiplies by 2 each attempt (500ms → 1000ms → 2000ms).

This means if Wallet Service is temporarily down, Transaction Service will automatically retry before giving up.

---

## 8. Notification Service

**Package:** `com.payflux.notification_service`  
**Port:** `8084`  
**Database:** PostgreSQL

Listens to Kafka for completed transactions and creates notifications for both sender and receiver.

### 8.1 Entity

**`Notification` entity**:
| Field         | Type          | Notes                              |
|---------------|---------------|------------------------------------|
| id            | Long          | Auto-generated                     |
| userId        | Long          | Who this notification is for       |
| transactionId | Long          | Which transaction caused it        |
| message       | String        | Human-readable message             |
| sentAt        | LocalDateTime | When the notification was created  |

### 8.2 Kafka Consumer (`NotificationConsumer.java`)

```java
@KafkaListener(topics = "txn-initiated", groupId = "notification-group")
public void consumerTransaction(Transaction transaction) { ... }
```

**What it does when a Kafka message arrives:**
1. **Idempotency check:** `notificationRepository.existsByTransactionIdAndUserId(...)`.
   - Prevent duplicate notifications if Kafka delivers the same message twice.
2. **For receiver:** Create notification: `"₹{amount} received from user {senderId}"`.
3. **For sender:** Create notification: `"₹{amount} sent to user {receiverId}"`.
4. Save both to the database.

> **Important:** Notification Service defines its own local `Transaction` class (a POJO) to deserialize the Kafka message. It is NOT a JPA entity — just a data transfer object.

### 8.3 API Endpoints

| Method | URL                            | What It Does                   |
|--------|--------------------------------|--------------------------------|
| POST   | `/api/notifications`           | Manually create a notification |
| GET    | `/api/notifications/{userId}`  | Get all notifications for user |

The POST endpoint is mostly for testing. In production, notifications are created automatically via Kafka.

---

## 9. Reward Service

**Package:** `com.payflux.reward_service`  
**Port:** `8089`  
**Database:** PostgreSQL

Listens to Kafka and grants reward points to the sender after every successful transaction.

### 9.1 Entity

**`Reward` entity**:
| Field         | Type          | Notes                               |
|---------------|---------------|-------------------------------------|
| id            | Long          | Auto-generated                      |
| userId        | Long          | Who earned the reward (the sender)  |
| transactionId | Long          | Which transaction earned it         |
| points        | Double        | `amount * 100` (e.g., ₹500 → 50000 points) |
| sentAt        | LocalDateTime | When reward was granted             |

### 9.2 Kafka Consumer (`RewardConsumer.java`)

```java
@KafkaListener(topics = "txn-initiated", groupId = "reward-group")
public void consumerTransaction(Transaction transaction) { ... }
```

**What it does:**
1. Check if reward already exists for this `transactionId` (idempotency).
2. Calculate points: `amount * 100`.
3. Assign reward to **sender** (not receiver).
4. Save to DB.

> Notification and Reward both consume from the same topic `"txn-initiated"` but with **different group IDs**. Kafka delivers the same message to BOTH groups independently. This is the publish-subscribe pattern.

### 9.3 API Endpoints

| Method | URL                          | What It Does               |
|--------|------------------------------|----------------------------|
| GET    | `/api/rewards`               | Get all rewards            |
| GET    | `/api/rewards/user/{userId}` | Get rewards for a user     |

---

## 10. Kafka: The Messaging Bus

### What Is Kafka?

Kafka is a **distributed message broker**. Instead of Service A calling Service B directly (tight coupling), Service A **publishes a message to a topic**. Service B (and C, D...) **subscribe to that topic** and process the message independently.

### Key Concepts Used In This Project

| Concept       | What It Is                                                           |
|---------------|----------------------------------------------------------------------|
| **Topic**     | A named channel. This project uses `"txn-initiated"`.               |
| **Producer**  | Transaction Service publishes to the topic.                          |
| **Consumer**  | Notification and Reward services read from the topic.               |
| **Group ID**  | Each consumer group gets its own copy of every message.             |
| **Offset**    | Kafka remembers what each consumer group has read.                  |

### This Project's Kafka Flow

```
Transaction Service (Producer)
    │
    │  publishes Transaction object as JSON
    ▼
Topic: "txn-initiated"
    │
    ├──► Notification Service (groupId: "notification-group")
    │         Reads message, creates 2 notifications (sender + receiver)
    │
    └──► Reward Service (groupId: "reward-group")
              Reads message, creates 1 reward for sender
```

### Configuration

**Producer (Transaction Service `application.yml`):**
```yaml
kafka:
  bootstrap-servers: localhost:9092
  producer:
    key-serializer: StringSerializer
    value-serializer: JsonSerializer
```

**Consumers** both have `@KafkaListener` annotations. The `JacksonConfig` in both notification and reward services configures `ObjectMapper` to correctly deserialize the `Transaction` JSON back into the local Transaction POJO.

### Why Not Just Use REST?

If Transaction Service called Notification and Reward via REST:
- Transaction would wait for both to respond (slower).
- If Notification Service is down, the transfer might fail/need rollback.
- Tight coupling: adding a new downstream service requires changing Transaction code.

With Kafka:
- Transaction publishes and moves on immediately (fire and forget).
- If a consumer is down, Kafka holds the message until it comes back up.
- New consumers can be added without touching Transaction Service.

---

## 11. Key Design Patterns Used

### 11.1 Idempotency Pattern

**Where:** Wallet Service (credit, debit, hold), Transaction Service (transaction creation)  
**Why:** Network retries are common. The same request might arrive twice. Idempotency ensures a payment happens exactly once.

**Implementation:**
- Client sends a unique `idempotencyKey` with every request.
- Service checks its `idempotency_records` table before processing.
- If key exists → return cached response. Else → process and save.

### 11.2 Saga Pattern (Compensation/Rollback)

**Where:** Transaction Service `createTransaction()`  
**Why:** A money transfer involves multiple steps across services. If step 3 fails, steps 1 and 2 must be undone.

**Implementation (manual saga):**
- Step 2 (Credit) fails → compensate by releasing the hold from Step 1.
- No distributed transaction (2PC) — instead, explicit compensation logic.

### 11.3 Hold-Capture Pattern

**Where:** Wallet Service + Transaction Service  
**Why:** Prevent double-spending without locking the database row.

- **Hold:** Reserve money (reduce `availableBalance`)
- **Capture:** Finalize the debit (reduce `balance`)
- **Release:** Cancel the reservation (restore `availableBalance`)

This is exactly how credit card authorizations work: the bank holds funds when you swipe, then settles later.

### 11.4 Retry with Exponential Backoff

**Where:** `WalletRestClient.java` in Transaction Service  
**Why:** Wallet Service might be temporarily unavailable.

```java
@Retryable(retryFor = ResourceAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
```
- Attempt 1: immediate
- Attempt 2: wait 500ms
- Attempt 3: wait 1000ms
- Give up after 3 attempts

### 11.5 Gateway + Feign + RestTemplate (Different HTTP Client Strategies)

| Where                         | HTTP Client   | Why                                              |
|-------------------------------|---------------|--------------------------------------------------|
| User Service → Wallet Service | Feign Client  | Declarative, simple interface — used for one-time wallet creation |
| Transaction Service → Wallet  | RestTemplate  | Manual control needed for retry logic and error handling |

---

## 12. Complete Money Transfer Flow (Step-by-Step)

**Scenario:** User 1 sends ₹500 to User 2.

```
1. CLIENT → POST /api/transactions/create
   Body: { senderId: 1, receiverId: 2, amount: 500, idempotencyKey: "uuid-xyz" }

2. API GATEWAY (port 8080)
   - JWT filter runs (currently soft — all paths are public)
   - Rate limiter checks Redis: is user within limit?
   - Route matches /api/transactions/** → forward to Transaction Service :8082

3. TRANSACTION SERVICE receives request
   - Check idempotency: "uuid-xyz" not in DB → proceed
   - Save Transaction { status: PENDING, idempotencyKey: "uuid-xyz" }
   - Generate: holdKey = "txn-7-hold", creditKey = "txn-7-credit"

4. TRANSACTION SERVICE → WALLET SERVICE (Hold)
   POST http://localhost:8083/api/v1/wallets/hold
   Body: { userId: 1, currency: "INR", amount: 500, idempotencyKey: "txn-7-hold" }
   
   Wallet Service:
   - Check idempotency for "txn-7-hold"
   - Find User 1's wallet (balance=₹1000, available=₹1000)
   - available < 500? No → proceed
   - available -= 500 → available = ₹500
   - Create WalletHold { ref: "HOLD-1712345", amount: 500, status: ACTIVE, expiresAt: now+5min }
   - Save idempotency record
   - Return { holdReference: "HOLD-1712345", amount: 500, status: "ACTIVE" }

5. TRANSACTION SERVICE saves holdReference = "HOLD-1712345" on the transaction.

6. TRANSACTION SERVICE → WALLET SERVICE (Credit Receiver)
   POST http://localhost:8083/api/v1/wallets/credit
   Body: { userId: 2, currency: "INR", amount: 500, idempotencyKey: "txn-7-credit" }
   
   Wallet Service:
   - Check idempotency for "txn-7-credit"
   - Find User 2's wallet (balance=₹200, available=₹200)
   - balance += 500 → balance = ₹700
   - available += 500 → available = ₹700
   - Save local transaction log (CREDIT, 500, SUCCESS)
   - Save idempotency record
   - Return WalletResponse

7. TRANSACTION SERVICE → WALLET SERVICE (Capture Hold)
   POST http://localhost:8083/api/v1/wallets/capture
   Body: { holdReference: "HOLD-1712345" }
   
   Wallet Service:
   - Find WalletHold by "HOLD-1712345"
   - hold.status = ACTIVE → proceed
   - User 1's wallet: balance -= 500 → balance = ₹500
   - hold.status = CAPTURED
   - Return WalletResponse

   Result: User 1 → balance=₹500, available=₹500 ✓
           User 2 → balance=₹700, available=₹700 ✓

8. TRANSACTION SERVICE sets transaction status = "SUCCESS", saves to DB.

9. TRANSACTION SERVICE publishes Kafka event:
   Topic: "txn-initiated"
   Key: "7"
   Value: { id:7, senderId:1, receiverId:2, amount:500, status:"SUCCESS", ... }

10. NOTIFICATION SERVICE (Kafka Consumer, group: notification-group)
    Receives the event:
    - Check: notification for txn 7 + userId 2 exists? No → Create:
      "₹500.0 received from user 1" → saved for User 2
    - Check: notification for txn 7 + userId 1 exists? No → Create:
      "₹500.0 sent to user 2" → saved for User 1

11. REWARD SERVICE (Kafka Consumer, group: reward-group)
    Receives the same event:
    - Check: reward for txn 7 exists? No → Create:
      { userId: 1, transactionId: 7, points: 50000, sentAt: now }
    - User 1 earned 50,000 reward points.

12. TRANSACTION SERVICE returns the saved transaction to the client:
    { id: 7, senderId: 1, receiverId: 2, amount: 500, status: "SUCCESS", ... }
```

---

## 13. How Services Talk To Each Other

### Direct HTTP (Synchronous) Calls

These are real-time, blocking calls where the caller waits for the response.

| From               | To             | Protocol     | Class Used                            | Purpose                       |
|--------------------|----------------|--------------|---------------------------------------|-------------------------------|
| User Service       | Wallet Service | HTTP (Feign) | `WalletClient.java`                   | Create wallet on signup       |
| Transaction Service| Wallet Service | HTTP (RestTemplate) | `WalletRestClient.java`         | Hold, Credit, Capture, Release |

### Event-Driven (Asynchronous) Calls via Kafka

These are non-blocking. Transaction Service publishes and continues. Consumers process independently.

| From               | Kafka Topic      | To                  | Class Used                   |
|--------------------|------------------|---------------------|------------------------------|
| Transaction Service| `txn-initiated`  | Notification Service| `KafkaEventProducer.java` (producer), `NotificationConsumer.java` (consumer) |
| Transaction Service| `txn-initiated`  | Reward Service      | `KafkaEventProducer.java` (producer), `RewardConsumer.java` (consumer) |

---

## 14. Database Tables

Each service has its **own database** (microservice principle: database-per-service).

### User Service DB

| Table      | Key Columns                          |
|------------|--------------------------------------|
| `app_user` | id, name, email (unique), password (BCrypt), role |

### Wallet Service DB

| Table               | Key Columns                                               |
|---------------------|-----------------------------------------------------------|
| `wallets`           | id, user_id, currency, balance, available_balance         |
| `wallet_holds`      | id, wallet_id (FK), hold_reference, amount, status, expires_at |
| `transactions`      | id, wallet_id, type (CREDIT/DEBIT), amount, status        |
| `idempotency_records` | idempotency_key (unique), operation, response_json      |

### Transaction Service DB

| Table         | Key Columns                                                              |
|---------------|--------------------------------------------------------------------------|
| `transaction` | id, sender_id, receiver_id, amount, timestamp, status, idempotency_key (unique), hold_reference |

### Notification Service DB

| Table           | Key Columns                             |
|-----------------|-----------------------------------------|
| `notifications` | id, user_id, transaction_id, message, sent_at |

### Reward Service DB

| Table     | Key Columns                                     |
|-----------|-------------------------------------------------|
| `rewards` | id, user_id, transaction_id, points, sent_at    |

---

## 15. How JWTs Work In This Project

JWT = **JSON Web Token**. It's a way to prove identity without a database lookup every time.

### Structure of a JWT

```
Header.Payload.Signature

Header: { "alg": "HS256", "typ": "JWT" }
Payload: { 
  "sub": "raj@gmail.com",   ← email (subject)
  "role": "ROLE_USER",
  "userId": "1",
  "iat": 1712345678,         ← issued at
  "exp": 1712349278          ← expiry
}
Signature: HMACSHA256(base64(header) + "." + base64(payload), secret)
```

### The Secret Key

All services that verify JWTs use the same secret:
```
"secret123secret123secret123secret123secret123secret123secret123secret123secret123"
```
> **Red flag (security note):** In production, this secret should be in environment variables, not hardcoded in source code.

### Token Lifecycle

1. User logs in via `POST /auth/login`.
2. User Service creates a JWT with `userId` and `role` as claims.
3. Client stores the token.
4. Client sends `Authorization: Bearer <token>` on every request.
5. API Gateway's `JwtAuthFilter` validates the token using the same secret.
6. If valid → extracts `email`, `userId`, `role` and adds them as request headers.
7. Downstream services can read `X-User-Id`, `X-User-Email`, `X-User-Role` headers without re-validating.

---

## 16. Rate Limiting With Redis

**Why?** Prevent abuse — a single user/IP shouldn't be able to flood the system with thousands of requests per second.

**How it works (Token Bucket Algorithm):**
- Imagine each user has a bucket that holds up to **20 tokens** (burstCapacity).
- Every second, **10 new tokens** are added (replenishRate).
- Each request consumes **1 token**.
- If bucket is empty → HTTP 429 Too Many Requests.

**Redis stores the token count** per user. This works because Redis is a fast, in-memory data store — checking and decrementing a counter is extremely fast.

**Key identification:**
- Authenticated users: identified by `X-User-Id` header (per-user limiting).
- Unauthenticated: identified by IP address.
- No info at all: key = `"anonymous"`.

---

## 17. Environment Setup & Running Locally

### Prerequisites

- Java 17+
- Maven
- PostgreSQL running locally
- Docker (for Kafka + Zookeeper)
- Redis running locally (port 6379)

### Start Kafka & Zookeeper

```bash
# From project root
docker-compose up -d
```

This starts:
- Zookeeper on port 2181
- Kafka on port 9092

### Set Environment Variables

Run `set-env.bat` in the root folder before starting services. This sets:
- `DB_URL` — e.g., `jdbc:postgresql://localhost:5432/payflux`
- `DB_USERNAME`
- `DB_PASSWORD`

### Start Services (Order Matters!)

1. **Wallet Service** (port 8083) — must be up before User Service
2. **User Service** (port 8081)
3. **Transaction Service** (port 8082)
4. **Notification Service** (port 8084)
5. **Reward Service** (port 8089)
6. **API Gateway** (port 8080) — start last

Each service is a separate Maven project. To run:
```bash
# Go into each service directory
mvn spring-boot:run
# Or run the main application class from your IDE
```

### Quick Test Flow

```bash
# 1. Register User 1
POST http://localhost:8080/auth/signup
{ "name": "Alice", "email": "alice@test.com", "password": "pass123" }

# 2. Register User 2
POST http://localhost:8080/auth/signup
{ "name": "Bob", "email": "bob@test.com", "password": "pass123" }

# 3. Login (get token)
POST http://localhost:8080/auth/login
{ "email": "alice@test.com", "password": "pass123" }
→ Returns: { "token": "eyJ..." }

# 4. Credit Alice's wallet (add money)
POST http://localhost:8080/api/v1/wallets/credit
{ "userId": 1, "currency": "INR", "amount": 1000 }

# 5. Send money from Alice (id=1) to Bob (id=2)
POST http://localhost:8080/api/transactions/create
{ "senderId": 1, "receiverId": 2, "amount": 300.0 }

# 6. Check Bob's wallet
GET http://localhost:8080/api/v1/wallets/2

# 7. Check notifications for Alice (id=1)
GET http://localhost:8080/api/notifications/1

# 8. Check reward points for Alice
GET http://localhost:8080/api/rewards/user/1
```

---

## Quick Revision Cheat Sheet

| Question                                    | Answer                                                            |
|---------------------------------------------|-------------------------------------------------------------------|
| What is the entry point?                    | API Gateway on port 8080                                          |
| How is auth handled?                        | JWT issued by User Service, validated by Gateway's JwtAuthFilter  |
| How is double-spending prevented?           | Hold-Capture pattern + two-balance model                          |
| What happens if a transfer fails midway?    | Saga compensation: hold is released, transaction marked FAILED    |
| How are notifications/rewards triggered?    | Kafka event published after SUCCESS, consumers react async        |
| What prevents duplicate payments?           | Idempotency keys stored in `idempotency_records` table            |
| What happens if a hold is never captured?   | HoldExpiryScheduler releases it after 5 minutes                  |
| Which services use Feign?                   | User Service (to create wallet)                                   |
| Which services use RestTemplate?            | Transaction Service (to call Wallet Service)                      |
| How are retries handled?                    | `@Retryable` with exponential backoff (500ms → 1s → 2s)          |
| What are the Kafka topic & groups?          | Topic: `txn-initiated`, Groups: `notification-group`, `reward-group` |
| What is the reward formula?                 | `amount * 100` points to the sender                               |
