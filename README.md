# Edge Rate Limiter

A microservices-based distributed notification architecture designed to ingest notification events, process them asynchronously, and dispatch emails.

This project serves as a practical demonstration platform built around a custom **Distributed Rate Limiter Subsystem**, showcasing advanced rate-limiting strategies, multi-dimension hierarchies, local batch leasing, and Redis-backed state management.

---

## Problem Statement

High-throughput, distributed applications require an edge rate-limiting subsystem capable of protecting downstream resources from traffic surges without introducing unacceptable latency. Traditional rate limiters often rely on rigid, single-dimension counters (e.g., global IP limits) and struggle with high network overhead due to constant remote state synchronization.

To solve this, the rate limiter in this architecture is built as a highly customizable, multi-tier subsystem. It combines Redis-backed atomic evaluation with local batch token leasing, enforcing rate limits across dynamic identity dimensions (User, Tier, IP) while drastically reducing remote network round-trips under heavy loads.

---

### Functional Requirements

* **Multi-Dimensional Rate Limiting**
    * Evaluate incoming requests across multiple concurrent identity dimensions: User ID, User Tier (e.g., Free, Pro, Enterprise), and Client IP.
    * Resolve limits dynamically based on caller identity extracted either from internal JWT claims or upstream fallback headers (`X-User-Id`, `X-User-Tier`, `X-Forwarded-For`).

* **Multiple Strategy Support**
    * Support configurable rate-limiting algorithms, including Sliding Window Logs, Token Buckets, and Multi-Window limits.
    * Allow rules, capacities, and time windows to be defined declaratively via external configuration files (YAML/Properties).

* **Local Batch Leasing Strategy**
    * Acquire and maintain token leases locally in application memory to serve consecutive request checks without hitting the central store on every request.
    * Dynamically sync unused or consumed lease counts back to Redis upon lease expiration or batch depletion.

* **Bypass & Action Handlers**
    * Support conditional bypass flag (`rate-limiting.required=false`) to disable rate evaluation completely for specific deployment environments.
    * Immediately reject non-compliant traffic with HTTP 429 (Too Many Requests) and standardized error headers when limits are exhausted.

---

### Non-Functional Requirements

* **Sub-Millisecond Overhead & High Throughput**
    * Process rate-check evaluations in under 5 millisecond per request, ensuring zero perceptible degradation to edge gateway latency.

* **Approximate Limits**
    * Each Lua script runs atomically in Redis, but checking a limit and recording a request are separate steps. Concurrent requests can therefore briefly exceed a limit, and leased tokens held in an instance's memory add further slack.
    * Exact enforcement is not a goal. The limiter trades a small, bounded overshoot for fewer Redis round trips and no locking. The size of that overshoot is not yet measured.

* **Network Efficiency**
    * Minimize remote Redis I/O operations through local token batching, pipelining, and efficient SHA-1 script caching.

* **Resilience & Fault Tolerance**
    * Include configurable fallback execution policies (e.g., fail-open or fail-closed) in the event of Redis downtime or network degradation to ensure system availability.

* **Horizontal Scalability**
    * Run multiple application instances against one shared Redis instance. Redis Cluster is not supported, because a request's keys (user, IP and global) live in different hash slots.
    * Instances keep no state other than leased tokens, which are lost if an instance stops.

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
* [`docs/monitoring-docs.md`](./docs/monitoring-docs.md): Monitoring setup and theory.

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

These headers are trusted only when `authentication.required=false`. That mode is safe only if:

* The service is reachable only through the gateway.
* The gateway removes any client-supplied `X-User-Id` and `X-User-Tier`, then sets them from the identity it verified.
* The gateway overwrites `X-Forwarded-For` with the client IP rather than appending to the client's value. The limiter uses the whole header as the IP key.

When `authentication.required=true`, the service ignores all three headers and removes them before proxying. Identity and tier come only from the JWT, and the client IP comes from the connection. Behind a trusted proxy, set `server.forward-headers-strategy` to resolve the real client IP. In proxy mode, requests without a valid JWT get a `401`.

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

Secrets come from environment variables, and the services won't start without them. Copy the template and fill in your own values:

```bash
cp .env.example .env
```

`JWT_SECRET` must be at least 32 bytes. Never commit `.env`.

Non-secret settings stay in `eventposting/src/main/resources/application.properties`:

```properties
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
