package com.notificationapp.ratelimiter.store;

import com.notificationapp.ratelimiter.model.WindowRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class RedisRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> tokenBucketLeaseScript;
    private final Clock clock; // Injected Spring Clock bean

    public RedisRateLimitStore(StringRedisTemplate redisTemplate,
                               @Qualifier("tokenBucketLeaseScript") RedisScript<Long> tokenBucketScript,
                               Clock clock) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketLeaseScript = tokenBucketScript;
        this.clock = clock;
    }

    /**
     * Executes the atomic Lua script in Redis to grant a batch lease.
     */
    public long leaseTokenBucket(String key, long capacity, double refillRatePerMs, int requestedBatch) {
        long currentAppTimeMs = clock.millis();

        return redisTemplate.execute(
                tokenBucketLeaseScript,
                Collections.singletonList(key),
                String.valueOf(capacity),
                String.valueOf(refillRatePerMs),
                String.valueOf(requestedBatch),
                String.valueOf(currentAppTimeMs)
        );
    }

    /**
     * Calculates retry duration by querying current state without consuming tokens.
     */
    public Duration getRetryAfter(String key, WindowRule rule) {
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
