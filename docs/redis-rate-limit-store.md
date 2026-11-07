# Redis Operations in `RedisRateLimitStore`

The `RedisRateLimitStore` class is the rate limiter's interface to Redis. It runs the check and commit scripts, leases token batches, computes retry times and applies the fallback policy when Redis fails.

---

## 1. Script Execution (`redisTemplate.execute`)

* **Description**: Runs `RedisScript` beans through Spring Data's `StringRedisTemplate`. Spring sends `EVALSHA` first and falls back to `EVAL` if Redis has lost the script, for example after a restart.
* **Application in `RedisRateLimitStore`**:
    * **Checks**: `evaluateFixedWindowRead`, `evaluateSlidingWindowCounterRead`, `evaluateSlidingWindowLogRead` and `inspectTokenBucketRead` check capacity without changing state.
    * **Commits**: `commitFixedWindowToPipeline`, `commitSlidingWindowCounterToPipeline`, `commitSlidingWindowLogToPipeline` and `commitTokenBucketToPipeline` record the request.
    * **Token leasing**: `leaseTokenBucket` grants a batch of tokens to an instance using `tokenBucketWriteScript`.
    * **Atomic check-and-record**: `evaluate*Atomic` methods exist for the unused `tryConsume` path.

---

## 2. Commit Batch (`executePipeline`)

* **Description**: `executePipeline(List<RedisCommitModel>)` runs every commit for a request, across all dimensions, inside `redisTemplate.executePipelined`.
* **Caveats**:
    * A pipeline is not a transaction. If one commit fails, earlier commits in the batch stay applied.
    * Each commit runs through `redisTemplate.execute`, which may use its own connection rather than the pipelined one. Whether the commits are actually sent as one pipeline has not been verified.
    * Any exception returns `false`. `RateLimitFilter` then replies `503` under `FAIL_CLOSED`, or lets the request through under `FAIL_OPEN`.

---

## 3. Hash Lookup (`opsForHash().multiGet`)

* **Description**: Reads several fields of a Redis hash with one `HMGET`.
* **Application in `RedisRateLimitStore`**:
    * **`getTokenBucketRetryAfter(String key, WindowRule rule)`**: Reads `tokens` and `lastRefill` and computes how long until one token is available. This call is not wrapped in the fallback handling described in section 5.

---

## 4. Counter Increment (`opsForValue().increment`)

* **`incrementAndGet(String key, long delta, Duration ttl)`**: Runs `INCRBY` and sets a TTL. No strategy currently calls it.

---

## 5. Fallback Handling (`rate-limiter.fallback-policy`)

* **Description**: Check and lease calls catch `RedisConnectionFailureException` and `RedisSystemException` and apply the configured policy:
    * **`FAIL_CLOSED`** (default): The check fails, so the request is rejected.
    * **`FAIL_OPEN`**: The check passes, so the request is let through without rate limiting.
* **Caveats**:
    * Under `FAIL_CLOSED`, a Redis outage is reported to the client as `429 Too Many Requests`, the same response as a real limit breach.
    * Other exception types, such as command timeouts, are not caught here.
