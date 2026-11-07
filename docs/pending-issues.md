# Pending Issues

Issues found in the November 2026 review that are not yet fixed. Numbers match the original review list, so gaps are issues already fixed (see "Fixed" at the end).

---

## Blocking

| # | Issue | Where | Notes |
|---|---|---|---|
| 5 | `/actuator/**` is public | `SecurityConfig.java` | Property values are now hidden (`show-values=NEVER`), but `health` details and `configprops` keys are still visible without logging in |
| 6 | Old secrets are still in git history | Repo history | The files now read secrets from environment variables. The old JWT secret, database password and Mailtrap credentials must be changed wherever they are used |

---

## Rate limiter accuracy

| # | Issue | Where | Notes |
|---|---|---|---|
| 10 | Check and commit are separate Redis calls, so concurrent requests can exceed a limit | `CompositeRateLimiter`, `RateLimitFilter` | Accepted: limits are approximate by design. Still to do: measure the overshoot with a concurrent test and replace "not yet measured" in the README |
| 12 | Leased tokens never expire or return to Redis | `TokenBucketStrategy`, `LocalTokenBucket` | An idle instance can hold tokens others can't use, and spend them much later |
| 13 | `localBuckets` is never evicted | `TokenBucketStrategy.java` | Grows with the number of distinct users and IPs |
| 15 | Token bucket keys always expire after one hour | `token_bucket_write.lua` | A bucket with a window longer than an hour resets to full after an hour idle |
| 28 | Paths hidden because cost is always 1 | `*_read.lua`, `RedisRateLimitStore.leaseTokenBucket` | Read scripts ignore cost, and a fail-open lease grants 1 token. Partial lease grants are now kept |
| New | Reserved local tokens are lost when a commit fails | `RateLimitFilter`, `TokenBucketStrategy` | Under `FAIL_CLOSED` the request gets `503` but its reserved tokens are not released |

---

## Failure handling

| # | Issue | Where | Notes |
|---|---|---|---|
| 16 | Redis outages look like rate limiting | `DimensionLimiter`, `RedisRateLimitStore.getTokenBucketRetryAfter` | Under `FAIL_CLOSED` clients get `429` instead of `503`. Token bucket rules can return `500`, because the retry lookup isn't guarded |
| 17 | Redis command timeouts are probably not caught | `RedisRateLimitStore` | Only `RedisConnectionFailureException` and `RedisSystemException` are handled |
| 18 | Latency doesn't match the stated target | `application.properties`, `DimensionLimiter` | Redis timeout is 2000 ms against a 5 ms target, and rules are checked one Redis call at a time |
| 19 | Proxy is fragile | `RateLimitFilter.proxyRequestToExternalUrl` | No timeouts, connection errors become `500`, and the path and query string are dropped |
| New | Publishing can block a request thread for up to a minute | `EventPublisherService` | `kafkaTemplate.send(...).get()` has no timeout, so it waits up to the producer's `max.block.ms` when Kafka is down |

---

## Kafka consumer

| # | Issue | Where | Notes |
|---|---|---|---|
| 20 | No dead-letter topic; bad messages are dropped or retried pointlessly | `NotificationConsumer`, `KafkaConsumerConfig` | Malformed JSON is acknowledged and dropped. Validation and mail failures are retried by the default error handler. `app.kafka.retry.*` is unused |
| 21 | Duplicate emails, and personal data in logs | `NotificationConsumer` | Mail is sent before the offset is acknowledged, with no idempotency check. Full payloads, including email addresses, are logged at INFO |

---

## Auth endpoints (only with `authentication.required=true`)

| # | Issue | Where | Notes |
|---|---|---|---|
| 22 | Login and signup are not rate limited, and login timing can reveal usernames | `RateLimitFilter`, `AuthController` | BCrypt is skipped for unknown usernames |

---

## Tests, observability and tooling

| # | Issue | Where | Notes |
|---|---|---|---|
| 23 | Remaining test and observability gaps | `eventposting/src/test`, `grafana/` | No integration test sends concurrent requests. Two `ChaosEdgeTest` cases only check for zero errors. No Testcontainers, so tests need a disposable Redis and flush it. No allow/deny metrics, Grafana dashboards or CI |
| New | `MultiWindowDimensionTest.testFreeUserFirstWindowBreached` is flaky | `MultiWindowDimensionTest` | Fixed windows align to clock seconds, so a run crossing a second boundary admits 3 requests instead of 2. Failed 2 of 6 reruns |
| New | `notificationgenerator`'s only test fails outside Docker | `NotificationgeneratorApplicationTests` | It needs a Kafka broker at `kafka:9092` |
| New | `notification-generator` doesn't answer on host port 8082 | `docker-compose.yml`, `notificationgenerator` `application.properties` | Prometheus scrapes it on port 8086 inside the Docker network, but `curl localhost:8082/actuator/health` failed in the end-to-end run |
| New | Commit pipelining is unverified | `RedisRateLimitStore.executePipeline` | Each commit runs through `redisTemplate.execute`, which may not use the pipelined connection |

---

## Docs and cleanup

| # | Issue | Where | Notes |
|---|---|---|---|
| 25 | README performance claims are unsupported | `README.md` | "Sub-millisecond" and "under 5 ms" with no benchmark |
| 26 | No `Retry-After` header on `429` | `RateLimitFilter`, `DimensionLimiter` | The retry time appears only in the message and rounds short waits down to "0s" |
| 27 | Sliding log retry time uses fixed-window logic | `SlidingWindowLogStrategy.retryAfter` | Should use the oldest entry in the window |
| 29 | Dead code | `web/`, `scripts/`, `store/`, `strategy/` | `RateLimitInterceptor`, `RateLimitResponseWriter`, `token_bucket_lease.lua`, the atomic `*.lua` scripts and `tryConsume`, stub store methods, unused `LocalTokenBucket` constructor arguments |
| 30 | Misleading names and messages | `CompositeRateLimiter`, `RedisRateLimitStore`, `RateLimitFilter` | "Atomic pipelined commit" isn't atomic. The lease error log says "Failing closed" under either policy. A token without a `tier` claim causes a `500` |
| 31 | Repo hygiene | Repo root, `Config/`, Dockerfiles, `docker-compose.yml` | `spring-shell.log` committed, `rete-limit-config.yml` typo, no LICENSE, `show-sql` and `ddl-auto=update` on, Docker builds skip tests, `:latest` images, Grafana admin password in Compose |
| New | Compose publishes Postgres on port 5432 | `docker-compose.yml` | Clashes with any other local Postgres; the port isn't needed when only the app connects |

---

## Fixed

| # | Fix |
|---|---|
| 1–4 | Identity headers (`X-User-Id`, `X-User-Tier`, `X-Forwarded-For`) are trusted only with `authentication.required=false`. With `true`, identity comes from the JWT, the IP from the connection, proxying needs a valid JWT, and client identity headers are stripped before proxying |
| 7 | The controller publishes to Kafka again |
| 8 | Ports, `EXPOSE` and Prometheus targets match the services; actuator requests skip the limiter |
| 9 | Commits fall back to `EVAL` when Redis has lost its script cache |
| 11 | Local token checks reserve atomically, and reservations are released when a later rule rejects |
| 14 | Removed the "zero state modified" comment |
| 24 | Docs match the code |
| — | Gateway mode starts without auth beans or a database; secrets come from environment variables |
