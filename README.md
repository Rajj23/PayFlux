<div align="center">

# 💳 PayFlux

**A microservices-based payment system built with Spring Boot & Kafka**

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Kafka-Async%20Events-231F20?logo=apachekafka&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

---



## Quickstart

```bash
# 1. Start Kafka & Zookeeper
docker-compose up

# 2. In separate terminals, start each service
cd api-gateway        && mvn spring-boot:run
cd user-service       && mvn spring-boot:run
cd wallet-service     && mvn spring-boot:run
cd transaction-service && mvn spring-boot:run
cd notification-service && mvn spring-boot:run
cd reward-service     && mvn spring-boot:run
```

```bash
# 3. Try it out (gateway on http://localhost:8080)
POST /auth/signup          # create user + wallet
POST /auth/login           # get JWT token
POST /api/transactions/create   # transfer money (JWT required)
GET  /api/notifications/{userId}
GET  /api/rewards/user/{userId}
```

### Key Features

| Feature | Description |
|---------|-------------|
| Microservices | 6 independent Spring Boot services |
| JWT Auth | Token-based auth enforced at the API Gateway |
| Transactions | Debit/credit with automatic compensation on failure |
| Kafka Events | Async notifications & rewards via `txn-initiated` topic |
| Concurrency | Pessimistic locking on wallet operations |
| Hold/Capture | 2-phase wallet mechanism (pre-auth style) |

---

## Architecture

### Services & Ports

| Service | Port | Role |
|---------|------|------|
| API Gateway | `8080` | Routing, JWT validation, rate limiting |
| User Service | `8081` | Signup, login, JWT issuance |
| Transaction Service | `8082` | Money transfers, Kafka producer |
| Wallet Service | `8083` | Balances, holds, debit/credit |
| Notification Service | `8084` | Kafka consumer — stores notifications |
| Reward Service | `8089` | Kafka consumer — assigns reward points |
| Kafka | `9092` | Event broker (Docker) |
| Zookeeper | `2181` | Kafka coordination (Docker) |

All services use **H2 in-memory** databases.

### Tech Stack

![Java](https://img.shields.io/badge/-Java%2017-ED8B00?logo=openjdk&logoColor=white&style=flat-square)
![Maven](https://img.shields.io/badge/-Maven-C71A36?logo=apachemaven&logoColor=white&style=flat-square)
![Spring Cloud Gateway](https://img.shields.io/badge/-Spring%20Cloud%20Gateway-6DB33F?logo=spring&logoColor=white&style=flat-square)
![Spring Security](https://img.shields.io/badge/-Spring%20Security%20+%20JWT-6DB33F?logo=springsecurity&logoColor=white&style=flat-square)
![JPA + H2](https://img.shields.io/badge/-Spring%20Data%20JPA%20+%20H2-6DB33F?logo=spring&logoColor=white&style=flat-square)
![Kafka](https://img.shields.io/badge/-Apache%20Kafka-231F20?logo=apachekafka&logoColor=white&style=flat-square)
![OpenFeign](https://img.shields.io/badge/-OpenFeign-6DB33F?logo=spring&logoColor=white&style=flat-square)
![Docker](https://img.shields.io/badge/-Docker%20Compose-2496ED?logo=docker&logoColor=white&style=flat-square)

### Service Communication

```
Client ──▶ API Gateway (8080)
               ├── /auth/**            ──▶ User Service (8081)
               ├── /api/transactions/** ──▶ Transaction Service (8082)
               ├── /api/v1/wallets/**  ──▶ Wallet Service (8083)
               ├── /api/notifications/**──▶ Notification Service (8084)
               └── /api/rewards/**     ──▶ Reward Service (8089)

User Service ──[Feign]──▶ Wallet Service        (auto-create wallet on signup)
Transaction Service ──[REST]──▶ Wallet Service   (debit/credit on transfer)
Transaction Service ──[Kafka: txn-initiated]──▶ Notification + Reward Services
```

### ER Diagram

![ER Diagram](Er-Diagram.png)

---

## Running the System

### 1. Start Kafka

```bash
docker-compose up
```

Starts **Zookeeper** (`localhost:2181`) and **Kafka** (`localhost:9092`).

### 2. Start Services

Run each in a separate terminal (or from your IDE):

```bash
cd api-gateway          && mvn spring-boot:run
cd user-service         && mvn spring-boot:run
cd wallet-service       && mvn spring-boot:run
cd transaction-service  && mvn spring-boot:run
cd notification-service && mvn spring-boot:run
cd reward-service       && mvn spring-boot:run
```

> **Note:** Rate limiting requires Redis on the default port. Without it, disable the rate limiter in gateway config.

---

## Request Flows

### Signup — `POST /auth/signup`

1. Gateway allows without JWT.
2. **User Service** validates email uniqueness, saves user, then calls **Wallet Service** (Feign) to auto-create a wallet.
3. If wallet creation fails, the user is deleted (rollback).

### Login — `POST /auth/login`

1. Gateway allows without JWT.
2. **User Service** verifies credentials (BCrypt), returns a JWT containing `userId`, `email`, `role`.
3. Use the token as `Authorization: Bearer <token>` for all other endpoints.

### Gateway JWT Filter

- Public paths (`/auth/signup`, `/auth/login`) — no token needed.
- Everything else — validates JWT, injects `X-User-Email`, `X-User-Id`, `X-User-Role` headers, or returns **401**.

### Money Transfer — `POST /api/transactions/create`

1. Creates a `PENDING` transaction.
2. **Debits** sender wallet — if it fails, marks `FAILED`.
3. **Credits** receiver wallet — if it fails, **refunds sender**, marks `FAILED`.
4. On success, publishes to Kafka topic `txn-initiated`.

### Notifications & Rewards (async via Kafka)

- **Notification Service** consumes `txn-initiated`, stores a notification record.
- **Reward Service** consumes the same topic (different consumer group), assigns `amount × 100` reward points. Deduplicates by `transactionId`.

### Wallet Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/wallets` | Create wallet |
| POST | `/api/v1/wallets/credit` | Credit money |
| POST | `/api/v1/wallets/debit` | Debit money |
| GET | `/api/v1/wallets/{userId}` | Get wallet |
| POST | `/api/v1/wallets/hold` | Place a hold (reserve funds) |
| POST | `/api/v1/wallets/capture` | Capture a held amount |
| POST | `/api/v1/wallets/release/{holdReference}` | Release a hold |

> Hold/capture is a 2-phase mechanism (like card pre-auth). Currently unused by the transfer flow but available for future use.

---

## Edge Cases & Error Handling

| Scenario | Behavior |
|----------|----------|
| Duplicate email on signup | Error returned, no user created |
| Wallet creation fails on signup | User is deleted (rollback) |
| Invalid/missing JWT | Gateway returns **401** |
| Insufficient funds | Transaction marked `FAILED` |
| Sender wallet not found | Transaction marked `FAILED` |
| Credit to receiver fails | Sender refunded, transaction marked `FAILED` |
| Kafka publish fails | Transaction stays `SUCCESS`; notifications/rewards may be missing |
| Duplicate Kafka event | Reward service deduplicates by `transactionId` (unique constraint) |
| Concurrent wallet ops | Pessimistic locking prevents race conditions |
| Insufficient balance for hold | Error thrown |

---

## Project Map

<details>
<summary><b>Click to expand full file locations</b></summary>

| Service | Key Files |
|---------|-----------|
| **API Gateway** | `application.yml` · `JwtAuthFilter.java` · `RateLimitConfig.java` · `JWTUtil.java` |
| **User Service** | `AuthController` · `UserController` · `UserServiceImpl` · `WalletClient.java` (Feign) |
| **Wallet Service** | `WalletController` · `WalletServiceImpl` · `Wallet` / `WalletHold` entities |
| **Transaction Service** | `TransactionController` · `TransactionServiceImpl` · `KafkaEventProducer` |
| **Notification Service** | `NotificationController` · `NotificationConsumer` (Kafka) |
| **Reward Service** | `RewardController` · `RewardConsumer` (Kafka) · `RewardRepository` |

</details>

---

## Future Improvements

- Replace H2 with **PostgreSQL / MySQL**
- Use **hold/capture** flow for transfers
- Send notifications to **receiver** (not just sender)
- Add **service discovery** (Eureka / Consul)
- Externalize JWT secret to environment variables

