# Rate Limiter Configuration Guide

This document defines the configuration schema and syntax for the rate-limiting subsystem. Configuration rules are declared under the `rate-limiter` root key.
For a complete example of the YAML setup, refer to the [Rate Limiter Configuration File](../eventposting/src/test/resources/Config/combination-dimension-test.yml).
---

## 1. Global & Fallback Properties

* **`rate-limiter.enabled`** (`boolean`): Enables or disables rate-limiting evaluation globally across the service.
* **`rate-limiter.fallback-policy`** (`enum`): Dictates system behavior when backing storage (Redis) is unreachable:
    * `FAIL_CLOSED`: Rejects incoming requests on failure to guarantee downstream protection.
    * `FAIL_OPEN`: Permits incoming requests on failure to prioritize endpoint availability.
* **`rate-limiter.dimension-order`** (`list`): Defines the strict execution hierarchy in which rate-limiting dimensions are evaluated. Evaluation stops early if any dimension denies a request.

---

## 2. Dimension Rules & Configuration Syntax

Rate limits are evaluated across three primary dimensions: **GLOBAL**, **IP_ADDRESS**, and **USER_ID**.

### Global Dimension (`global:`)
Enforces system-wide traffic thresholds regardless of client identity or IP address.

* **`windows`**: Array of time-window evaluation rules:
    * **`size`**: Duration of the rate window (e.g., `1s`, `1m`, `1h`).
    * **`limit`**: Maximum allowed request/token count within the window.
    * **`strategy`**: Algorithm type (`TOKEN_BUCKET`, `FIXED_WINDOW`, `SLIDING_WINDOW_COUNTER`, `SLIDING_WINDOW_LOG`).
    * **`local-batch`**: Enables local in-memory token leasing to reduce Redis I/O overhead (applicable to `TOKEN_BUCKET` strategy):
        * **`enabled`**: Toggles local instance batching on/off.
        * **`batch-size`**: Number of tokens requested from Redis in a single lease reservation.
        * **`refill-threshold`**: Low-token trigger point that prompts the local node to asynchronously re-lease a new token batch.

---

### IP Address Dimension (`ip:`)
Enforces rate limits scoped strictly to individual client IP addresses.

* **`windows`**: Array of time-window evaluation rules specifying `size`, `limit`, and `strategy`.

---

### User Identity Dimension (`user:`)
Enforces tier-based rate limits scoped to authenticated user accounts.

* **`tiers`**: Defines custom rule sets mapped to user tier classifications (e.g., `FREE`, `PRO`, `ENTERPRISE`):
    * **`[TIER_NAME].windows`**: Array of time-window rules specifying `size`, `limit`, and `strategy` for that tier level.

---