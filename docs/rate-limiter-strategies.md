# Rate Limiting Strategies

This section documents the algorithms supported by the `strategy/` package.

Every request is handled in two phases. **Check** (`canConsume`) decides whether the request fits, and **commit** (`commit`) records it once every dimension has passed. The two phases are separate Redis calls, so concurrent requests can briefly exceed a limit. Limits are approximate by design; see [Approximate Limits](../README.md).

---

## 1. Rate Limiting Strategy Implementations

### `FixedWindowStrategy.java`
* **Mechanism**: Divides time into fixed, non-overlapping intervals (e.g., 1-minute windows) and counts requests within the current window.
* **Pros**: Simple, memory-efficient and fast.
* **Cons**: Allows bursts at window boundaries. A client can send a full window's limit at the end of one window and another full limit at the start of the next.

---

### `SlidingWindowLogStrategy.java`
* **Mechanism**: Stores every request timestamp in a Redis sorted set (`ZSET`). The check counts entries inside the window, and the commit adds the new entry and prunes old ones.
* **Pros**: Smooth rolling window with no boundary bursts.
* **Cons**: Memory and CPU cost grow with request volume, because every request is stored.

---

### `SlidingWindowCounterStrategy.java`
* **Mechanism**: Keeps a counter per fixed window and estimates the rolling count by weighting the previous window by how much of it still overlaps:
  $$\text{Estimated Count} = \lfloor \text{Previous Count} \times \frac{\text{Window} - \text{Elapsed}}{\text{Window}} \rfloor + \text{Current Count}$$
* **Pros**: Low memory (two integers per key) while smoothing boundary bursts.
* **Cons**: An approximation, because it assumes requests in the previous window were evenly spread.

---

### `TokenBucketStrategy.java`
* **Mechanism**: A bucket of capacity $C$ refills at a steady rate $R$ tokens per unit time, and each request consumes tokens. State (`tokens`, `lastRefill`) lives in a Redis hash.
* **Pros**: Allows short bursts while enforcing an average rate over longer periods.
* **Cons**: Without local batching, every request needs a Redis check and a Redis commit.

---

### `LocalTokenBucket.java` & Batch Leasing
With `local-batch.enabled: true`, an instance leases a batch of tokens from Redis and serves requests from memory until the batch runs out.

#### Execution Flow
1. **Reserve locally**: The check atomically takes the request's tokens from the instance's in-memory bucket (a compare-and-set on an `AtomicLong`). Concurrent requests cannot spend the same token.
2. **Lease when empty**: If the local bucket is short, the check synchronously leases a batch from Redis (`batch-size`, capped at the bucket's capacity) using `token_bucket_write.lua`. Every granted token is kept locally, including partial grants.
3. **Release on rejection**: If a later rule or dimension rejects the request, the reserved tokens go back to the local bucket.
4. **Commit**: Nothing to do, because the tokens were reserved during the check.

#### Limitations
* Leased tokens never expire and are never returned to Redis. An idle instance can hold tokens that other instances cannot use, and can spend them long after they were leased.
* Leased tokens are lost if an instance stops.
* The in-memory map holds one bucket per key and is never evicted, so it grows with the number of distinct users and IPs.
* `refill-threshold` is validated but not used. Leasing happens only when the local bucket is empty.

---

## 2. Local Leasing vs. a Centralized Token Bucket

| Factor | Centralized Redis Token Bucket | Local Batch Leasing |
| :--- | :--- | :--- |
| **Redis round trips** | A check and a commit on every request | One lease per batch; requests in between stay in memory |
| **Latency** | Bound by Redis round-trip time | In-memory for most requests; a request that triggers a lease waits for Redis |
| **Accuracy** | Limited only by the check/commit gap | Looser: unused leased tokens on other instances add slack, so total admissions can exceed the limit until those tokens are spent |
| **Redis outage** | Every check fails and the fallback policy applies | Instances keep serving from tokens they already hold, then the fallback policy applies |
