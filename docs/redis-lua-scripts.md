# Redis Lua Scripting Architecture

This section explains why the rate limiter uses Lua scripts, which scripts the request path runs, and what atomicity they do and do not provide.

The limiter is designed for **one Redis instance**. Redis Cluster is not supported, because a request touches user, IP and global keys that live in different hash slots.

---

## 1. Why Lua Scripts Are Used

Rate-limiting algorithms read state (counters, logs, token counts), compute a decision and write state back. Doing this as separate commands such as `GET` followed by `SET` has two problems:

1. **Interleaving**: Another client can change the key between the read and the write, so both act on stale data.
2. **Round trips**: Each command is a separate network round trip.

Redis runs each Lua script to completion before running any other command, so **a single script is atomic** and costs one round trip. This is what removes interleaving inside one script, without external locks.

**What this does not cover**: the request path uses a separate script to check and another to commit (see section 2). Those are two calls, and another request can run between them. Concurrent requests can therefore briefly exceed a limit. This is an accepted trade-off; limits are approximate by design.

---

## 2. Scripts Used on the Request Path

Each strategy has a read script and a write script:

| Phase | Scripts | Called from |
| :--- | :--- | :--- |
| **Check** | `*_read.lua` | `canConsume` via `RedisRateLimitStore.evaluate*Read` and `inspectTokenBucketRead` |
| **Commit** | `*_write.lua` | `commit` via `RedisRateLimitStore.commit*ToPipeline`, run from `executePipeline` |
| **Lease** (token bucket with local batching) | `token_bucket_write.lua` | `canConsume` via `RedisRateLimitStore.leaseTokenBucket` |

The split exists so a request that fails one dimension is not charged in the dimensions it already passed: nothing is written until every dimension has been checked.

### Unused Scripts
* `fixed_window.lua`, `sliding_window_counter.lua` and `sliding_window_log.lua` check and record in one atomic step. Only each strategy's `tryConsume` method calls them, and nothing on the request path calls `tryConsume`.
* `token_bucket_lease.lua` has the same content as `token_bucket_write.lua` and is never called.

---

## 3. Script Execution and the Script Cache

* All scripts run through `StringRedisTemplate.execute(RedisScript, ...)`. Spring sends `EVALSHA` and, if Redis replies `NOSCRIPT`, falls back to `EVAL`, which also caches the script again.
* Redis empties its script cache on restart. Because of the fallback, the first call after a restart reloads each script instead of failing.
* `RedisTransactionScriptConfig` also loads every script into the cache when the application starts.

---

## 4. Script Summary

| Script | Purpose |
| :--- | :--- |
| **`fixed_window_read.lua`** | Checks whether the current window count plus the request cost fits the limit. |
| **`fixed_window_write.lua`** | Increments the current window counter and sets its expiry. |
| **`sliding_window_counter_read.lua`** | Computes the weighted estimate from the previous and current window counters. |
| **`sliding_window_counter_write.lua`** | Increments the current window counter and sets its expiry. |
| **`sliding_window_log_read.lua`** | Counts log entries inside the window. |
| **`sliding_window_log_write.lua`** | Adds the request to the log, prunes entries outside the window and sets the key's expiry. |
| **`token_bucket_read.lua`** | Applies refill and checks whether at least one whole token is available. |
| **`token_bucket_write.lua`** | Applies refill, grants up to the requested number of whole tokens and saves the bucket. Used for commits and for leasing batches. |
| **`fixed_window.lua`**, **`sliding_window_counter.lua`**, **`sliding_window_log.lua`** | Atomic check-and-record variants, currently unused. |
| **`token_bucket_lease.lua`** | Duplicate of `token_bucket_write.lua`, currently unused. |
