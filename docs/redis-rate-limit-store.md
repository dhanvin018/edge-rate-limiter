# Redis Operations in `RedisRateLimitStore`

The `RedisRateLimitStore` class serves as the low-level communication interface with Redis. It handles synchronous evaluations, atomic script execution, hash inspection, key operations, and batch pipeline commits.

---

## 1. Lua Script Execution via SHA-1 Hashes (`evalSha`)

* **Description**: Executes pre-compiled Lua scripts cached on the Redis server using their SHA-1 digest. This eliminates sending full script strings over the wire for every call.
* **Application in `RedisRateLimitStore`**:
    * **Low-Level Callback Intercepts**: Used in methods like `commitFixedWindowToPipeline`, `commitSlidingWindowCounterToPipeline`, `commitSlidingWindowLogToPipeline`, and `commitTokenBucketToPipeline` via `connection.evalSha()`.
    * **Optimized Payload Delivery**: Sends parameters encoded as binary UTF-8 byte arrays directly to the low-level `RedisConnection` layer.

---

## 2. High-Level Template Executions (`redisTemplate.execute`)

* **Description**: Leverages Spring Data's `StringRedisTemplate` to execute named `RedisScript` instances with structured keys and arguments.
* **Application in `RedisRateLimitStore`**:
    * **Atomic Evaluations**: Handles synchronous strategy evaluations (such as `evaluateFixedWindowAtomic`, `evaluateSlidingWindowCounterAtomic`, and `evaluateSlidingWindowLogAtomic`).
    * **Read-Only Inspections**: Executes non-mutating checks like `evaluateFixedWindowRead`, `evaluateSlidingWindowCounterRead`, and `inspectTokenBucketRead` to inspect limit capacity without modifying underlying state counters.
    * **Token Leasing**: Triggers `leaseTokenBucket` via `tokenBucketWriteScript` to allocate token batches to local application instances.

---

## 3. Redis Pipelining (`redisTemplate.executePipelined`)

* **Description**: Batches multiple commands into a single network payload sent to Redis. Redis processes the commands sequentially without returning intermediate responses until the entire batch finishes, drastically reducing network round-trip overhead ($RTT$).
* **Application in `RedisRateLimitStore`**:
    * **`executePipeline(List<RedisCommit> commitItems)`**: Takes an array of pending `RedisCommit` objects accrued during local processing phases and flushes all deferred write operations (across different dimensions and strategies) in a single pipeline execution.

---

## 4. Hash Multi-Key Inspection (`opsForHash().multiGet`)

* **Description**: Fetches the values of multiple fields from a Redis Hash key in a single atomic lookup command (`HMGET`).
* **Application in `RedisRateLimitStore`**:
    * **`getTokenBucketRetryAfter(String key, WindowRule rule)`**: Queries the `"tokens"` and `"lastRefill"` fields simultaneously for a given token bucket key.
    * **Retry Computation**: Calculates the exact remaining wait time (in milliseconds) before a client can retry a rate-limited request without triggering state mutations.

---

## 5. String Counter Invalidation & Increments (`opsForValue().increment`)

* **Description**: Executes the native Redis `INCRBY` command, atomically incrementing an integer string value by a specified delta and returning the updated balance.
* **Application in `RedisRateLimitStore`**:
    * **`incrementAndGet(String key, long delta, Duration ttl)`**: Provides atomic increments for simple key-value rate limiting scenarios where Lua scripts are not required.

---

## 6. Key Expiration Management (`redisTemplate.expire`)

* **Description**: Sets a Time-To-Live (TTL) expiration on a target Redis key, ensuring obsolete rate-limiting keys are automatically evicted from memory.
* **Application in `RedisRateLimitStore`**:
    * **`incrementAndGet(String key, long delta, Duration ttl)`**: Conditionally attaches a TTL duration to newly initialized counter keys to prevent unbounded memory growth.

---

## 7. Resilience & Fallback Handling (`rate-limiter.fallback-policy`)

* **Description**: Wraps Redis operations in try-catch blocks targeting connection and system exceptions (`RedisConnectionFailureException`, `RedisSystemException`).
* **Application in `RedisRateLimitStore`**:
  * **Configurable Fallback Behavior**: System behavior during Redis outages or connection failures is governed dynamically by the `rate-limiter.fallback-policy` property:
    * **Fail-Closed (`FAIL_CLOSED`)**: Rejects traffic by returning `false` (or `0L`) when Redis is unreachable, protecting downstream services from unthrottled traffic spikes.
    * **Fail-Open (`FAIL_OPEN`)**: Permits traffic to pass through during Redis downtime, prioritizing service availability over strict rate enforcement.