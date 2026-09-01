package com.notificationapp.ratelimiter.store;

import com.notificationapp.ratelimiter.config.FallbackPolicy;
import com.notificationapp.ratelimiter.model.RedisCommitModel;
import com.notificationapp.ratelimiter.model.WindowRule;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class RedisRateLimitStore implements RateLimitStore {

    @Value("${rate-limiter.fallback-policy:FAIL_CLOSED}")
    private FallbackPolicy fallbackPolicy;

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

    private final StringRedisTemplate redisTemplate;

    private final RedisScript<Long> tokenBucketReadScript;
    private final RedisScript<Long> tokenBucketWriteScript;

    private final RedisScript<Long> fixedWindowScript;
    private final RedisScript<Long> fixedWindowReadScript;
    private final RedisScript<Long> fixedWindowWriteScript;

    private final RedisScript<Long> slidingWindowLogReadScript;
    private final RedisScript<Long> slidingWindowLogWriteScript;
    private final RedisScript<Long> slidingWindowLogScript;

    private final RedisScript<Long> slidingWindowCounterReadScript;
    private final RedisScript<Long> slidingWindowCounterScript;
    private final RedisScript<Long> slidingWindowCounterWriteScript;

    private final Clock clock; // Injected Spring Clock bean

    public RedisRateLimitStore(StringRedisTemplate redisTemplate,
                               @Qualifier("tokenBucketReadScript") RedisScript<Long> tokenBucketReadScript,
                               @Qualifier("tokenBucketWriteScript") RedisScript<Long> tokenBucketWriteScript,
                               @Qualifier("fixedWindowScript") RedisScript<Long> fixedWindowScript,
                               @Qualifier("fixedWindowReadScript") RedisScript<Long> fixedWindowReadScript,
                               @Qualifier("fixedWindowWriteScript") RedisScript<Long> fixedWindowWriteScript,
                               @Qualifier("slidingWindowLogScript") RedisScript<Long> slidingWindowLogScript,
                               @Qualifier("slidingWindowLogReadScript") RedisScript<Long> slidingWindowLogReadScript,
                               @Qualifier("slidingWindowLogWriteScript") RedisScript<Long> slidingWindowLogWriteScript,
                               @Qualifier("slidingWindowCounterReadScript") RedisScript<Long> slidingWindowCounterReadScript,
                               @Qualifier("slidingWindowCounterScript") RedisScript<Long> slidingWindowCounterScript,
                               @Qualifier("slidingWindowCounterWriteScript") RedisScript<Long> slidingWindowCounterWriteScript,
                               Clock clock) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketReadScript = tokenBucketReadScript;
        this.tokenBucketWriteScript = tokenBucketWriteScript;
        this.fixedWindowScript = fixedWindowScript;
        this.fixedWindowReadScript = fixedWindowReadScript;
        this.fixedWindowWriteScript = fixedWindowWriteScript;
        this.slidingWindowLogScript = slidingWindowLogScript;
        this.slidingWindowLogReadScript = slidingWindowLogReadScript;
        this.slidingWindowLogWriteScript = slidingWindowLogWriteScript;
        this.slidingWindowCounterReadScript = slidingWindowCounterReadScript;
        this.slidingWindowCounterScript = slidingWindowCounterScript;
        this.slidingWindowCounterWriteScript = slidingWindowCounterWriteScript;
        this.clock = clock;
    }
    @Override
    public boolean evaluateFixedWindowRead(String windowKey, long capacity, int cost) {
        try {
            Long result = redisTemplate.execute(
                    fixedWindowReadScript,
                    Collections.singletonList(windowKey),
                    String.valueOf(capacity),
                    String.valueOf(cost)
            );
            return result == 1L;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            return handleRedisException(e, "fixed window read evaluation", windowKey);
        }
    }

    @Override
    public boolean evaluateFixedWindowAtomic(String windowKey, long capacity, long windowSizeMs, int cost) {
        try {
            Long result = redisTemplate.execute(
                    fixedWindowScript,
                    Collections.singletonList(windowKey),
                    String.valueOf(capacity),
                    String.valueOf(windowSizeMs),
                    String.valueOf(cost)
            );
            return result == 1L;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            return handleRedisException(e, "fixed window atomic evaluation", windowKey);
        }
    }

    @Override
    public void commitFixedWindowToPipeline(String windowKey, int cost, long windowSizeMs) {

        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.evalSha(
                    fixedWindowWriteScript.getSha1(),
                    ReturnType.INTEGER,
                    1,
                    windowKey.getBytes(StandardCharsets.UTF_8),
                    String.valueOf(cost).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(windowSizeMs).getBytes(StandardCharsets.UTF_8)
            );
            return null;
        });
    }

    @Override
    public boolean evaluateSlidingWindowCounterRead(String currentKey, String previousKey, long capacity, long windowMs, long now, int cost) {
        try {
            Long result = redisTemplate.execute(
                    slidingWindowCounterReadScript,
                    List.of(currentKey, previousKey),
                    String.valueOf(capacity),
                    String.valueOf(windowMs),
                    String.valueOf(now),
                    String.valueOf(cost)
            );
            return result == 1L;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            return handleRedisException(e, "sliding window counter read evaluation", currentKey);
        }
    }

    @Override
    public boolean evaluateSlidingWindowCounterAtomic(String currentKey, String previousKey, long capacity, long windowMs, long now, int cost) {
        try {
            Long result = redisTemplate.execute(
                    slidingWindowCounterScript,
                    List.of(currentKey, previousKey),
                    String.valueOf(capacity),
                    String.valueOf(windowMs),
                    String.valueOf(now),
                    String.valueOf(cost)
            );
            return result == 1L;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            return handleRedisException(e, "sliding window counter atomic evaluation", currentKey);
        }
    }

    @Override
    public void commitSlidingWindowCounterToPipeline(String currentKey, int cost, long windowMs) {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.evalSha(
                    slidingWindowCounterWriteScript.getSha1(),
                    ReturnType.INTEGER,
                    1,
                    currentKey.getBytes(StandardCharsets.UTF_8),
                    String.valueOf(cost).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(windowMs).getBytes(StandardCharsets.UTF_8)
            );
            return null;
        });
    }

    @Override
    public boolean evaluateSlidingWindowLogRead(String key, long capacity, long windowMs, long now) {
        try {
            Long result = redisTemplate.execute(
                    slidingWindowLogReadScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(windowMs),
                    String.valueOf(now)
            );
            return result == 1L;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            return handleRedisException(e, "sliding window log read evaluation", key);
        }
    }

    @Override
    public boolean evaluateSlidingWindowLogAtomic(String key, long capacity, long windowMs, long now, String uniqueMemberId) {
        try {
            Long result = redisTemplate.execute(
                    slidingWindowLogScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(windowMs),
                    String.valueOf(now),
                    uniqueMemberId
            );
            return result == 1L;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            return handleRedisException(e, "sliding window log read evaluation", key);
        }
    }

    @Override
    public void commitSlidingWindowLogToPipeline(String key, long windowMs, long now, String uniqueMemberId) {
        // Active pipeline execution during executePipeline pass
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.evalSha(
                    slidingWindowLogWriteScript.getSha1(),
                    ReturnType.INTEGER,
                    1,
                    key.getBytes(StandardCharsets.UTF_8),
                    String.valueOf(windowMs).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(now).getBytes(StandardCharsets.UTF_8),
                    uniqueMemberId.getBytes(StandardCharsets.UTF_8)
            );
            return null;
        });
    }

    @Override
    public boolean executePipeline(List<RedisCommitModel> commitItems) {
        if (commitItems == null || commitItems.isEmpty()) {
            log.trace("No items to commit to Redis pipeline.");
            return true;
        }

        log.debug("Starting Phase 2 Redis pipelined commit for {} item(s)", commitItems.size());

        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (RedisCommitModel item : commitItems) {
                    log.trace("Enqueuing write script for key [{}] via strategy [{}]",
                            item.key(), item.strategy().type());

                    item.strategy().commit(
                            item.key(),
                            item.rule(),
                            item.cost(),
                            this
                    );
                }
                return null;
            });

            log.debug("Successfully flushed Phase 2 commit pipeline across all dimensions.");
            return true;

        } catch (Exception e) {
            log.error("Failed to commit rate limit state to Redis during Phase 2 pipeline. " +
                    "Applying Fallback Strategy (Fail Closed / Reject). Error: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean inspectTokenBucketRead(String key, long capacity, double refillRatePerMs, int cost) {
        try {
            Long result = redisTemplate.execute(
                    tokenBucketReadScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerMs),
                    String.valueOf(cost),
                    String.valueOf(clock.millis())
            );
            return result != null && result == 1L;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            return handleRedisException(e, "token bucket read evaluation", key);
        }
    }

    @Override
    public long leaseTokenBucket(String key, long capacity, double refillRatePerMs, int requestedBatch) {
        long currentAppTimeMs = clock.millis();
        try {
            Long grantedTokens = redisTemplate.execute(
                    tokenBucketWriteScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerMs),
                    String.valueOf(requestedBatch),
                    String.valueOf(currentAppTimeMs)
            );
            return grantedTokens != null ? grantedTokens : 0L;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable during token bucket lease for key: {}. Failing closed.", key, e);
            if (fallbackPolicy == FallbackPolicy.FAIL_CLOSED) {
                // FAIL_CLOSED: Log warning and deny request to proceed down the filter chain
                log.error("Fail-Closed activated for key: {} during token bucket lease operation", key, e);
                return 0L;
            } else {
                // FAIL_OPEN: Log warning and allow request to proceed down the filter chain
                log.error("Fail-Open activated for key: {} during token bucket lease operation", key, e);
                return 1L;
            }
        }
    }

    @Override
    public void commitTokenBucketToPipeline(String key, long capacity, double refillRatePerMs, int cost, long now) {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.evalSha(
                    tokenBucketWriteScript.getSha1(),
                    ReturnType.INTEGER,
                    1,
                    key.getBytes(StandardCharsets.UTF_8),                            // KEYS[1]
                    String.valueOf(capacity).getBytes(StandardCharsets.UTF_8),       // ARGV[1]
                    String.valueOf(refillRatePerMs).getBytes(StandardCharsets.UTF_8), // ARGV[2]
                    String.valueOf(cost).getBytes(StandardCharsets.UTF_8),           // ARGV[3]
                    String.valueOf(now).getBytes(StandardCharsets.UTF_8)            // ARGV[4]
            );
            return null;
        });
    }

    /**
     * Calculates retry duration by querying current state without consuming tokens.
     */
    public Duration getTokenBucketRetryAfter(String key, WindowRule rule) {
        log.debug("Calculating retry duration for key: {}", key);
        List<Object> state = redisTemplate.opsForHash().multiGet(key, List.of("tokens", "lastRefill"));
        if (state.get(0) == null || state.get(1) == null) {
            log.debug("Key: {} has incomplete state or does not exist in Redis", key);
            return Duration.ZERO; // Bucket hasn't been created or is full
        }

        double currentTokens = Double.parseDouble(state.get(0).toString());
        long lastRefill = Long.parseLong(state.get(1).toString());

        double refillRatePerMs = (double) rule.limit() / rule.windowSize().toMillis();
        long elapsed = Math.max(0, clock.millis() - lastRefill);
        double actualTokens = Math.min(rule.effectiveCapacity(), currentTokens + (elapsed * refillRatePerMs));

        if (actualTokens >= 1.0) {
            return Duration.ZERO;
        }

        double needed = 1.0 - actualTokens;
        long waitTimeMs = (long) Math.ceil(needed / refillRatePerMs);
        Duration retryAfter = Duration.ofMillis(Math.max(0, waitTimeMs));
        log.debug("Retry duration calculated for key: {}: {}ms", key, retryAfter.toMillis());
        return retryAfter;
    }

    private boolean handleRedisException(Exception e, String operation, String key) {
        if (fallbackPolicy == FallbackPolicy.FAIL_CLOSED) {
            // FAIL_CLOSED: Log warning and deny request to proceed down the filter chain
            log.error("Fail-Closed activated for key: {}, operation: {}. Request denied due to limiter store operation failure.", key, operation, e);
            return false;
        } else {
            // FAIL_OPEN: Log warning and allow request to proceed down the filter chain
            log.error("Fail-Open activated for key: {}, operation: {}. Request allowed despite limiter store operation failure.", key, operation, e);
            return true;
        }
    }

    // --- Standard KV RateLimitStore interface methods ---
    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        // Implement default GET if needed by other non-batched strategies
        return Optional.empty();
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        // Implement default PUT
    }

    @Override
    public long incrementAndGet(String key, long delta, Duration ttl) {
        log.debug("Incrementing key: {} by delta: {}", key, delta);
        Long val = redisTemplate.opsForValue().increment(key, delta);
        if (ttl != null) {
            redisTemplate.expire(key, ttl);
        }
        return val != null ? val : 0L;
    }

    @Override
    public boolean compareAndSwap(String key, Object expected, Object updated, Duration ttl) {
        // Not used when delegating atomicity to Lua scripts
        return false;
    }
}
