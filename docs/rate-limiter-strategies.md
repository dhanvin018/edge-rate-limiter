# Rate Limiting Strategies

This section documents the algorithms supported by the `strategy/` package.

---

## 1. Rate Limiting Strategy Implementations

### `FixedWindowStrategy.java`
* **Mechanism**: Divides time into fixed, non-overlapping intervals (e.g., 1-minute windows) and tracks request counts within the current window boundary.
* **Pros**: Incredibly simple, memory-efficient, and fast to execute.
* **Cons**: Vulnerable to traffic spikes at window boundaries (e.g., sending double the allowed requests right at $t = 0.59s$ and $t = 1.01s$).

---

### `SlidingWindowLogStrategy.java`
* **Mechanism**: Stores every request timestamp in a dynamic log (such as a Redis Sorted Set / `ZSET`). When a request arrives, older entries outside the sliding duration are pruned, and the log size is evaluated.
* **Pros**: Perfectly smooth and exact sliding window evaluation with zero boundary burst vulnerability.
* **Cons**: High memory consumption and computational overhead for high-throughput endpoints due to storing individual request timestamps.

---

### `SlidingWindowCounterStrategy.java`
* **Mechanism**: Combines fixed window counters with a weighted calculation based on the previous window's request count. It estimates sliding window throughput using:
  $$\text{Estimated Count} = \text{Current Window Count} + (\text{Previous Window Count} \times (1 - \text{Overlap Percentage}))$$
* **Pros**: Low memory footprint (only retains two integers per client context) while eliminating hard boundary bursts.
* **Cons**: Provides an approximation rather than 100% strict precision.

---

### `TokenBucketStrategy.java`
* **Mechanism**: Maintains a bucket with a maximum capacity $C$ that refilled at a steady rate $R$ tokens per unit time. Incoming requests consume a set number of tokens.
* **Pros**: Natural support for traffic bursting while strictly enforcing average rate bounds over longer intervals.
* **Cons**: Requires frequent atomic state updates (tokens remaining + last refill timestamp) across concurrent execution threads.

---

### `LocalTokenBucket.java` & Batch Leasing Architecture
`LocalTokenBucket.java` works alongside `TokenBucketStrategy.java` to implement a **Local Token Bucket Leasing Scheme**.

Instead of forcing every single incoming HTTP request to make a synchronous network round-trip to Redis to fetch/consume 1 token, instances lease a batch of tokens locally.

#### Execution Flow
1. **Batch Reservation**: The local application node requests a batch lease (e.g., 50 tokens) from the centralized `RedisRateLimitStore`.
2. **In-Memory Consumption**: Subsequent HTTP requests on that server instance consume tokens directly from `LocalTokenBucket.java` in local RAM without hitting Redis.
3. **Re-leasing & Reconciliation**: When the local bucket reaches a lower threshold, it asynchronously requests another batch lease from Redis or returns unused leased tokens upon expiration.

---

## 2. Why Local Token Bucket Leasing Beats Fully Centralized Token Buckets

| Evaluation Factor | Centralized Redis Token Bucket | Local Token Bucket Leasing (`LocalTokenBucket.java`) |
| :--- | :--- | :--- |
| **Network Overhead** | **1 Request = 1 Redis Round-Trip**. High network I/O and latency amplification under heavy loads. | **1 Redis Round-Trip = $N$ Local Requests**. Drastically reduces network traffic between app nodes and Redis. |
| **Throughput & Latency** | Limited by network round-trip time (RTT) and Redis single-threaded command processing speed. | **Sub-millisecond latency**. Token checks execute as in-memory memory operations in application RAM. |
| **Redis Load & Contention** | High key contention under concurrent traffic bursts, increasing Redis CPU usage and lock contention. | **Low contention**. Redis only processes intermittent batch lease operations instead of every micro-transaction. |
| **Fault Resilience** | If Redis becomes temporarily sluggish or drops network frames, all app request threads freeze. | **Degrades gracefully**. If Redis experiences transient latency, instances continue serving traffic from their local active token lease buffers. |