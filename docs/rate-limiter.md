# Rate Limiter Module Technical Documentation

This document outlines the architecture, execution workflow, package structure, and individual component responsibilities for the `ratelimiter` subsystem within the `eventposting` microservice.

---

## 1. Process Flow Overview

`Inbound HTTP Request` ➔ **RateLimitFilter** ➔ **RateLimitContext** ➔ **CompositeRateLimiter.evaluate** ➔ **RateLimitDecision** ➔ **CompositeRateLimiter.commit**

1. **Request Interception**: `RateLimitFilter` runs inside the Spring Security filter chain. `/api/v1/auth/**` and `/actuator/**` skip the limiter.
2. **Context Resolution**: The filter builds a `RateLimitContext` from the user ID, tier and client IP. Where these come from depends on `authentication.required`; see the README's trust model section.
3. **Check**: `CompositeRateLimiter.evaluate` asks each `DimensionLimiter` in `dimension-order` to check its rules. The first rejection stops evaluation, and reservations made by rules that already passed are released.
4. **Decision**: A `RateLimitDecision` says whether the request is allowed, with a `DenyReason` if not, and carries the commits to apply.
5. **Response**:
    * **Denied**: The filter replies `429` with the deny reason as plain text.
    * **Allowed**: `CompositeRateLimiter.commit` records the request in Redis. If the commit fails, the filter replies `503` under `FAIL_CLOSED`, or continues under `FAIL_OPEN`. The request then reaches the controller, or is proxied downstream when `event.posting.enabled=false`.

---
## 2. Package & Class Breakdown

### Configuration (`config/`)
Manages configuration loading, validation, and fallback mechanisms for rate limiting rules.

* **`FallbackPolicy.java`**: Defines system behavior when rate-limiting dependencies fail (e.g., allow traffic vs. block traffic).
* **`RateLimitConfigProvider.java`**: Interface specifying methods to dynamically retrieve rate-limiting rules.
* **`PropertiesBackedConfigProvider.java`**: Implementation loading rate-limit configuration directly from Spring `.properties` files.
* **`YamlRateLimiterConfigProvider.java`**: Implementation parsing rate-limit rules from structured `.yml` configuration files.
* **`RateLimiterConfig.java`**: Central Spring `@Configuration` bean defining rate-limiter component initialization.
* **`RateLimitProperties.java`**: Binds externalized rate-limit properties (`rate-limiter.*`) to strongly typed Java fields.
* **`RateLimiterConfigValidator.java`**: Validates configuration rules on application startup to prevent invalid or conflicting limit thresholds.
* **`RedisTransactionScriptConfig.java`**: Defines a `RedisScript` bean per Lua script and loads them into Redis's script cache at startup.

---

### Core Limiter Logic (`limiter/`)
Orchestrates the entry point, decision execution, and combination of rate-limiting policies.

* **`RateLimitFilter.java`**: Servlet `Filter` integrated into Spring Security to intercept requests early in the processing chain.
* **`RateLimiter.java`**: Interface with two methods: `evaluate(context)` checks limits, and `commit(commitItems)` records an allowed request.
* **`CompositeRateLimiter.java`**: Runs one `DimensionLimiter` per dimension in order, and allows a request only if every dimension passes.
* **`DimensionLimiter.java`**: Checks every rule configured for one dimension (`USER_ID`, `IP_ADDRESS` or `GLOBAL`). The `USER_ID` and `IP_ADDRESS` dimensions are skipped when the request has no user ID or IP.
* **`RateLimiterFactory.java`**: Factory class that instantiates appropriate `RateLimiter` components based on configuration profiles.

---

### Models & State (`model/`)
Encapsulates data structures, contextual state, and evaluation results used throughout the lifecycle.

* **`RateLimitContext.java`**: Holds request metadata (user ID, IP address, user tier, request cost).
* **`RateLimitDecision.java`**: Encapsulates the outcome: allowed or denied, deny reason, violated rule, retry time, message and the commits to apply.
* **`DenyReason.java`**: Enum of block reasons: `RATE_EXCEEDED` and `COST_EXCEEDED`.
* **`Dimension.java`**: Enum of rate-limiting dimensions: `USER_ID`, `IP_ADDRESS` and `GLOBAL`. Tiers select which `USER_ID` rules apply.
* **`WindowRule.java`**: Model defining time-window thresholds (e.g., 100 requests per 60 seconds).
* **`TokenBucketState.java`**: Model tracking token counts, capacity, and last-refill timestamps.
* **`LocalBatchConfig.java`**: Configuration model governing local batching operations for high-throughput counters.
* **`RedisCommitModel.java`**: One pending commit: key, rule, strategy and cost.
* **`LimitStrategyType.java`**: Enum of supported algorithms: `FIXED_WINDOW`, `SLIDING_WINDOW_LOG`, `SLIDING_WINDOW_COUNTER` and `TOKEN_BUCKET`.

---

### Storage Layer (`store/`)
Abstracts storage operations so the limiter can interact with persistent state engines seamlessly.

* **`RateLimitStore.java`**: Storage contract interface defining operations for reading, incrementing, and resetting counter data.
* **`RedisRateLimitStore.java`**: Redis-backed implementation of `RateLimitStore`, shared by all application instances through one Redis instance.

---

### Strategies (`strategy/`)
Defines the algorithms used to calculate rate limits.

* **`LimitStrategy.java`**: Core strategy interface implemented by concrete rate-limiting algorithms.
* **`StrategyRegistry.java`**: Central registry maintaining active algorithm strategies for runtime lookup.
* **`FixedWindowStrategy.java`**: Evaluates request counts against rigid, non-overlapping time windows.
* **`SlidingWindowCounterStrategy.java`**: Approximates rolling window metrics using weighted counter estimates.
* **`SlidingWindowLogStrategy.java`**: Provides precise rolling window rate limiting using timestamp logs.
* **`TokenBucketStrategy.java`**: Implements token bucket dynamics to support smooth processing with controlled burst capacity.
* **`LocalTokenBucket.java`**: In-memory store of tokens leased from Redis, used when `local-batch` is enabled.

---

### Web Tier (`web/`)
Not currently used. `RateLimitFilter` replaced this interceptor-based integration, and `WebConfig` no longer registers the interceptor.

* **`RateLimitInterceptor.java`**: Spring `HandlerInterceptor` providing endpoint-level rate limit checks.
* **`RateLimitErrorResponse.java`**: DTO representing the error payload returned when a request is blocked (HTTP 429).
* **`RateLimitResponseWriter.java`**: Utility class that serializes `RateLimitErrorResponse` directly to the HTTP response output stream.
* **`WebConfig.java`**: Spring MVC configuration; the `RateLimitInterceptor` registration is commented out.