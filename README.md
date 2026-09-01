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

## 3. Pluggable Architecture & Routing

The `eventposting` service features a modular, pluggable architecture where key operational subsystems—such as authentication, rate limiting, and event publishing—can be independently enabled or disabled via `application.properties`.

When event posting is disabled, the service automatically acts as an identity-aware rate-limiting proxy, forwarding validated traffic to a designated downstream server.

---

### Component Configuration Toggles

| Property | Default | Behavior when `false` |
| :--- | :--- | :--- |
| `authentication.required` | `true` | Skips internal JWT validation and Security Filter evaluation. Useful when deployed behind an external API gateway. |
| `rate-limiting.required` | `true` | Bypasses the Redis-backed Distributed Rate Limiter subsystem entirely. |
| `event.posting.enabled` | `true` | Disables internal Kafka event publishing and enables **HTTP Proxy Mode**. |
| `external.downstream.url` | *(none)* | **Required when `event.posting.enabled=false`**. The target URL where incoming requests, headers, and payloads will be transparently forwarded. |

---

### Upstream Context & Header Ingestion

When internal authentication is disabled (e.g., offloaded to an upstream API Gateway or edge proxy), the rate-limiting engine dynamically falls back to extracting user identity and request context directly from inbound HTTP headers:

* **`X-User-Id`**: Ingested as the primary caller identifier (`username`) when local JWT context is unavailable.
* **`X-User-Tier`**: Ingested to determine tier-based rate limit limits (e.g., `free`, `pro`, `enterprise`).
* **`X-Forwarded-For`**: Ingested to extract the original client IP address (`ipAddress`) for IP-level rate-limiting rules.

---

## 4. Test Cases, Configurations & Sample Payloads

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

## 5. Setup & Running the System

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