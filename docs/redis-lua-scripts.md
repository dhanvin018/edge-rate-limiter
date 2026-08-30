# Redis Lua Scripting Architecture

This section documents the integration of Redis Lua scripts within the rate-limiting storage layer, explaining why Lua scripts are required, how atomic execution works, and the architectural separation between unified atomic scripts and decoupled Read/Write scripts.

---

## 1. Why Lua Scripts are Used for Rate Limiting

Evaluating rate-limiting algorithms directly inside a distributed store like Redis requires reading current state metrics (e.g., token counts, window request logs, last-refill timestamps), performing conditional checks, updating metrics, and re-writing the state back to memory.

Executing these operations as individual network commands (e.g., `GET` followed by `SET`) introduces two critical vulnerabilities:

1. **Race Conditions & Concurrency Issues**: Multiple concurrent request threads reading the same key simultaneously could calculate stale rate limits, leading to inaccurate token tracking or over-granting allowances under high load.
2. **High Network Latency**: Executing multiple distinct commands requires multiple network round-trips ($RTT$) between the application node and Redis.

### The Lua Script Solution
Redis executes Lua scripts **atomically** within a single-threaded execution context. When a Lua script runs:
* **Guaranteed Atomicity**: No other Redis command or script can interleave or modify state during execution, preventing race conditions without needing complex external locks.
* **Minimized Network Traffic**: The application sends a single evaluation request containing the script (or its `EVALSHA` hash) and arguments, executing all logical checks directly on the Redis server in a single round-trip.

---

## 2. Dual-Script Architecture: Single Atomic vs. Separated Read/Write Scripts

The `scripts/` package implements two distinct patterns for each rate-limiting strategy:

### Pattern A: Unified Single-Script Atomic Evaluation (`*.lua`)
* **Files**: `fixed_window.lua`, `sliding_window_counter.lua`, `sliding_window_log.lua`, `token_bucket_lease.lua`
* **Execution Pattern**: Performs the entire **Read $\rightarrow$ Evaluate $\rightarrow$ Mutate/Write** workflow inside a single script payload.

#### Primary Use Cases
* **High-Concurrency Rate Checks**: Used during active endpoint traffic processing when state must be checked and updated synchronously in one operation.
* **Token Batch Leasing**: `token_bucket_lease.lua` atomically verifies available token capacity in Redis, updates the centralized bucket, and reserves a lease batch for local application nodes.

---

### Pattern B: Decoupled Read and Write Operations (`*_read.lua` & `*_write.lua`)
* **Files**: `*_read.lua` and `*_write.lua` pairs for each algorithm (e.g., `fixed_window_read.lua` and `fixed_window_write.lua`).
* **Execution Pattern**: Splits state inspection (`Read`) and state mutation (`Write`) into separate, focused Lua scripts.

#### Why Separate Read and Write Scripts?

1. **Read-Only Inspection & Telemetry**:
    * Admin dashboards, monitoring endpoints, and health checks need to inspect remaining rate limits or window state without mutating counters or consuming tokens.
    * `*_read.lua` scripts safely fetch state without applying side effects or altering expiry timestamps.

2. **Asynchronous / Batch Commit Optimization**:
    * Under heavy local token bucket leasing or deferred batching schemes, an application instance may evaluate limits locally in memory and only flush accumulated state changes to Redis periodically.
    * `*_write.lua` scripts allow application nodes to push bulk state mutations or batch commits directly without executing evaluation logic again.

3. **Redis Read Replica Distribution**:
    * Separating read scripts enables routing `*_read.lua` operations to low-latency **Redis Read Replicas** while directing state updates (`*_write.lua`) exclusively to the primary Redis node, improving overall cluster scalability.

---

## 3. Script Variants Summary

| Script Name | Purpose & Execution Characteristics |
| :--- | :--- |
| **`fixed_window.lua`** | Atomically increments fixed window counters and sets key expiration in one pass. |
| **`fixed_window_read.lua`** | Inspects current fixed window request count without incrementing. |
| **`fixed_window_write.lua`** | Updates or resets fixed window counter state during administrative or batch operations. |
| **`sliding_window_counter.lua`** | Atomically fetches previous and current window counters to compute weighted estimates and update current counts. |
| **`sliding_window_counter_read.lua`** | Reads previous and current window counters for sliding estimation without mutating data. |
| **`sliding_window_counter_write.lua`** | Commits calculated window values to Redis. |
| **`sliding_window_log.lua`** | Prones old timestamp entries in a Redis `ZSET`, logs new request timestamps, and returns current window counts atomically. |
| **`sliding_window_log_read.lua`** | Queries log entry counts within the active time range without modifying the underlying `ZSET`. |
| **`sliding_window_log_write.lua`** | Manually appends or cleans log timestamps. |
| **`token_bucket_lease.lua`** | Calculates token refills based on elapsed time, verifies capacity, and deducts/reserves token leases atomically. |
| **`token_bucket_read.lua`** | Inspects current token availability and last-refill timestamps. |
| **`token_bucket_write.lua`** | Directly updates token balances or force-refills bucket capacities. |