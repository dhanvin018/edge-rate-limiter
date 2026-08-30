# Distributed Notification System

A microservices-based distributed notification architecture designed to ingest notification events, process them asynchronously, and dispatch emails.

This project serves as a practical demonstration platform built around a custom **Distributed Rate Limiter Subsystem**, showcasing advanced rate-limiting strategies, multi-dimension hierarchies, local batch leasing, and Redis-backed state management.

---

## 1. Project Architecture & Applications

The system is composed of two primary microservices alongside backing infrastructure (Kafka, Redis, PostgreSQL, and Mailtrap):

* **`eventposting`**: The entry-point API service. It authenticates inbound HTTP requests, evaluates configurable multi-tier rate limits, and publishes validated notification events to Kafka topics.
* **`notificationgenerator`**: An asynchronous worker service. It consumes event streams from Kafka, processes business logic, and dispatches formatted email notifications through Mailtrap.

---

## 2. Documentation Directory (`docs/`)

Detailed design and implementation documentation is available inside the [`docs/`](./docs) folder:

* [`docs/architecture.md`](./docs/architecture.md): Overall microservice architecture and system flow.
* [`docs/authentication.md`](./docs/authentication.md): Authentication implementation, SecurityConfig, and JWT management.
* [`docs/rate-limiter.md`](./docs/rate-limiter.md): Core rate-limiter architecture, process flow, and class breakdown.
* [`docs/rate-limiter-configuration.md`](./docs/rate-limiter-configuration.md): Schema, properties, and configuration syntax.
* [`docs/rate-limiter-strategies.md`](./docs/rate-limiter-strategies.md): Detailed breakdown of rate-limiting algorithms and local token leasing.
* [`docs/redis-lua-scripts.md`](./docs/redis-lua-scripts.md): Redis Lua script architecture, SHA-1 execution, and atomic vs. R/W separation.
* [`docs/redis-rate-limit-store.md`](./docs/redis-rate-limit-store.md): Low-level Redis storage layer, pipelining, and resilience policies.
* [`docs/docker-compose.md`](./docs/docker-compose.md): Docker container orchestration setup and theory.

---

## 3. Test Cases, Configurations & Sample Payloads

All test suites, strategy configurations, and sample request payloads for the rate limiter are located within the `eventposting` test resources:

### Test Suites
Located under [`eventposting/src/test/java/com/notificationapp/ratelimiter/`](./eventposting/src/test/java/com/notificationapp/ratelimiter/):
* **`suites/`**: Includes strategy-specific integration tests , multi-window tests, and edge case tests (`ChaosEdgeTest.java`).
* **`base/` & `utils/`**: Base setup helpers and synthetic clock utilities (`MutableClock.java`, `TestClockConfig.java`).

### Test Configurations & Payloads
Located under [`eventposting/src/test/resources/`](./eventposting/src/test/resources/):
* **`Config/`**: Contains YAML configuration setups for various testing scenarios.
* **`payloads/`**: Contains sample API request payloads for mail notifications (`sample-payload.json`).

---

## 4. Setup & Running the System

### Prerequisites
* Docker & Docker Compose
* Java 21+ & Maven (optional, if running outside containers)

---

### Configure Secret & Application Properties

Before spinning up the containers, configure your application settings in `eventposting/src/main/resources/application.properties` (or via environment variables):

**Update application properties**: :
   ```properties
   jwt.secret=YOUR_CUSTOM_SECURE_SECRET_KEY_HERE
   authentication.required=true
   rate-limiting.required=true
```
---

### Docker Commands

Run in detached mode to build and start all containers:
```bash
docker-compose up --build -d
```
Stream Application Logs
```bash
docker-compose logs -f eventposting notificationgenerator
```

Stop and remove all running project containers and networks:
```bash
docker-compose down
```