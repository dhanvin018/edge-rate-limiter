# Rate Limiter Module Technical Documentation

This document outlines the architecture, execution workflow, package structure, and individual component responsibilities for the `ratelimiter` subsystem within the `eventposting` microservice.

---

## 1. Process Flow Overview

`Inbound HTTP Request` ➔ **RateLimitFilter / Interceptor** ➔ **RateLimitContext** ➔ **RateLimiter / CompositeRateLimiter** ➔ **RateLimitDecision** ➔ **RateLimitResponseWriter**

1. **Request Interception**: Incoming HTTP requests are intercepted at the Web/Security tier (`RateLimitFilter` or `RateLimitInterceptor`).
2. **Context Resolution**: Client identity, user tier, request IP, and endpoint metadata are compiled into a `RateLimitContext`.
3. **Limiter Execution**: The context is evaluated by `CompositeRateLimiter`, which checks multiple rules across different dimensions via `DimensionLimiter`.
4. **Decision Generation**: A `RateLimitDecision` object is returned, indicating whether the request is allowed or denied (along with a `DenyReason` if blocked).
5. **Response Enforcement**:
    * **Allowed**: The request proceeds down the filter chain to core business logic.
    * **Denied**: `RateLimitResponseWriter` intercepts execution, halts the chain, and writes a structured `RateLimitErrorResponse` (HTTP 429 Too Many Requests).

---
## 2. Package & Class Breakdown

### Configuration (`config/`)
Manages configuration loading, validation, and fallback mechanisms for rate limiting rules.

* **`FallbackPolicy.java`**: Defines system behavior when rate-limiting dependencies fail (e.g., allow traffic vs. block traffic).
* **`RateLimitConfigProvider.java`**: Interface specifying methods to dynamically retrieve rate-limiting rules.
* **`PropertiesBackedConfigProvider.java`**: Implementation loading rate-limit configuration directly from Spring `.properties` files.
* **`YamlRateLimiterConfigProvider.java`**: Implementation parsing rate-limit rules from structured `.yml` configuration files.
* **`RateLimiterConfig.java`**: Central Spring `@Configuration` bean defining rate-limiter component initialization.
* **`RateLimitProperties.java`**: Binds externalized rate-limit properties (`ratelimit.*`) to strongly typed Java fields.
* **`RateLimiterConfigValidator.java`**: Validates configuration rules on application startup to prevent invalid or conflicting limit thresholds.
* **`RedisTransactionScriptConfig.java`**: Configures atomic script execution resources for rate-limiting operations.

---

### Core Limiter Logic (`limiter/`)
Orchestrates the entry point, decision execution, and combination of rate-limiting policies.

* **`RateLimitFilter.java`**: Servlet `Filter` integrated into Spring Security to intercept requests early in the processing chain.
* **`RateLimiter.java`**: Core interface contract defining the primary `isAllowed(context)` valuation method.
* **`CompositeRateLimiter.java`**: Combines multiple `RateLimiter` instances into a single chain, ensuring all rate limits pass before permitting execution.
* **`DimensionLimiter.java`**: Evaluates rate limits along specific target dimensions (e.g., per-user, per-IP, or per-tier).
* **`RateLimiterFactory.java`**: Factory class that instantiates appropriate `RateLimiter` components based on configuration profiles.

---

### Models & State (`model/`)
Encapsulates data structures, contextual state, and evaluation results used throughout the lifecycle.

* **`RateLimitContext.java`**: Holds request metadata (Client ID, User Tier, Target Endpoint, IP address).
* **`RateLimitDecision.java`**: Encapsulates evaluation outcome (`allowed=true/false`, remaining tokens/requests, reset timestamp).
* **`DenyReason.java`**: Enum detailing specific block triggers (e.g., `USER_LIMIT_EXCEEDED`, `IP_LIMIT_EXCEEDED`).
* **`Dimension.java`**: Enum specifying the rate-limiting target context (e.g., `USER`, `IP`, `GLOBAL`, `TIER`).
* **`WindowRule.java`**: Model defining time-window thresholds (e.g., 100 requests per 60 seconds).
* **`TokenBucketState.java`**: Model tracking token counts, capacity, and last-refill timestamps.
* **`LocalBatchConfig.java`**: Configuration model governing local batching operations for high-throughput counters.
* **`RedisCommitModel.java`**: Data model representing pending atomic state sync operations.
* **`LimitStrategyType.java`**: Enum listing supported algorithm types (e.g., `FIXED_WINDOW`, `TOKEN_BUCKET`, `SLIDING_WINDOW`).

---

### Storage Layer (`store/`)
Abstracts storage operations so the limiter can interact with persistent state engines seamlessly.

* **`RateLimitStore.java`**: Storage contract interface defining operations for reading, incrementing, and resetting counter data.
* **`RedisRateLimitStore.java`**: Redis-backed implementation of `RateLimitStore` managing state across distributed application instances.

---

### Strategies (`strategy/`)
Defines the algorithms used to calculate rate limits.

* **`LimitStrategy.java`**: Core strategy interface implemented by concrete rate-limiting algorithms.
* **`StrategyRegistry.java`**: Central registry maintaining active algorithm strategies for runtime lookup.
* **`FixedWindowStrategy.java`**: Evaluates request counts against rigid, non-overlapping time windows.
* **`SlidingWindowCounterStrategy.java`**: Approximates rolling window metrics using weighted counter estimates.
* **`SlidingWindowLogStrategy.java`**: Provides precise rolling window rate limiting using timestamp logs.
* **`TokenBucketStrategy.java`**: Implements token bucket dynamics to support smooth processing with controlled burst capacity.
* **`LocalTokenBucket.java`**: In-memory token bucket implementation for local instance execution.

---

### Web Tier (`web/`)
Handles HTTP interceptor hooks, error formatting, and WebMvc integration. 
Deprecated in favor of `RateLimitFilter` for Spring Security integration.

* **`RateLimitInterceptor.java`**: Spring `HandlerInterceptor` providing endpoint-level rate limit checks.
* **`RateLimitErrorResponse.java`**: DTO representing the error payload returned when a request is blocked (HTTP 429).
* **`RateLimitResponseWriter.java`**: Utility class that serializes `RateLimitErrorResponse` directly to the HTTP response output stream.
* **`WebConfig.java`**: Registers `RateLimitInterceptor` within Spring MVC's `InterceptorRegistry`.