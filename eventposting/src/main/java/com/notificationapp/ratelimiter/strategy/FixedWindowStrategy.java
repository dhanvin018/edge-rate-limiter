package com.notificationapp.ratelimiter.strategy;

import com.notificationapp.ratelimiter.model.LimitStrategyType;
import com.notificationapp.ratelimiter.model.WindowRule;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

@Component
public class FixedWindowStrategy implements LimitStrategy {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowStrategy.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> fixedWindowScript;
    private final RedisScript<Long> fixedWindowReadScript;
    private final RedisScript<Long> fixedWindowWriteScript;
    private final Clock clock;

    public FixedWindowStrategy(StringRedisTemplate redisTemplate,
                               @Qualifier("fixedWindowScript") RedisScript<Long> fixedWindowScript,
                               @Qualifier("fixedWindowReadScript") RedisScript<Long> fixedWindowReadScript,
                               @Qualifier("fixedWindowWriteScript") RedisScript<Long> fixedWindowWriteScript,
                               Clock clock) {
        this.redisTemplate = redisTemplate;
        this.fixedWindowScript = fixedWindowScript;
        this.fixedWindowReadScript = fixedWindowReadScript;
        this.fixedWindowWriteScript = fixedWindowWriteScript;
        this.clock = clock;
    }

    @Override
    public LimitStrategyType type() {
        return LimitStrategyType.FIXED_WINDOW;
    }

    @Override
    public boolean canConsume(String key, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowSizeMs = rule.windowSize().toMillis();

        long currentWindowBucket = now / windowSizeMs;
        String windowKey = key + ":" + currentWindowBucket;

        log.debug("Evaluating fixed window strategy for key: {}, windowKey: {}, cost: {}, capacity: {}",
                key, windowKey, cost, rule.effectiveCapacity());

        Long result = 0L;
        try {
            log.debug("REQ -> Key: {} | Limit: '{}' | Window: '{}' | Cost: '{}' | Timestamp: '{}'",
                    windowKey, rule.effectiveCapacity(), windowSizeMs, cost, Instant.now());
            result = redisTemplate.execute(
                    fixedWindowReadScript,
                    Collections.singletonList(windowKey),
                    String.valueOf(rule.effectiveCapacity()),
                    String.valueOf(cost)
            );
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable while evaluating rate limit for key: {}. Failing closed.", key, e);
            return false;
        }

        boolean allowed = result != null && result == 1L;
        log.debug("Fixed window consumption result for key: {}: allowed = {}", windowKey, allowed);
        return allowed;
    }

    @Override
    public void commit(String key, WindowRule rule, int cost, RateLimitStore store, RedisConnection connection) {
        long now = clock.millis();
        long windowSizeMs = rule.windowSize().toMillis();

        long currentWindowBucket = now / windowSizeMs;
        String windowKey = key + ":" + currentWindowBucket;

        log.debug("Committing fixed window strategy for windowKey: {}, cost: {}, windowSizeMs: {}",
                windowKey, cost, windowSizeMs);

        // Execute write script directly on low-level Redis pipeline connection
        connection.evalSha(
                fixedWindowWriteScript.getSha1(),
                org.springframework.data.redis.connection.ReturnType.INTEGER,
                1, // Number of KEYS
                windowKey.getBytes(StandardCharsets.UTF_8),                  // KEYS[1]
                String.valueOf(cost).getBytes(StandardCharsets.UTF_8),       // ARGV[1]
                String.valueOf(windowSizeMs).getBytes(StandardCharsets.UTF_8) // ARGV[2]
        );
    }

    @Override
    public boolean tryConsume(String key, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowSizeMs = rule.windowSize().toMillis();

        // Calculate the current discrete window bucket (e.g., timestamp divided by 60000)
        long currentWindowBucket = now / windowSizeMs;
        String windowKey = key + ":" + currentWindowBucket;

        log.debug("Evaluating fixed window strategy for key: {}, windowKey: {}, cost: {}, capacity: {}",
                key, windowKey, cost, rule.effectiveCapacity());

        Long result = 0L;
        try {
            log.debug("REQ -> Key: {} | Limit: '{}' | Window: '{}' | Cost: '{}' | Timestamp: '{}'",
                    windowKey, rule.effectiveCapacity(), windowSizeMs, cost, Instant.now());
            result = redisTemplate.execute(
                    fixedWindowScript,
                    Collections.singletonList(windowKey),
                    String.valueOf(rule.effectiveCapacity()),
                    String.valueOf(windowSizeMs),
                    String.valueOf(cost)
            );
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable while evaluating rate limit for key: {}. Failing closed.", key, e);
        }

        boolean allowed = result != null && result == 1L;
        log.debug("Fixed window consumption result for key: {}: allowed = {}", windowKey, allowed);
        return allowed;
    }

    @Override
    public Duration retryAfter(String key, WindowRule rule, RateLimitStore store) {
        long now = clock.millis();
        long windowSizeMs = rule.windowSize().toMillis();

        // Time remaining until the next window starts
        long timeIntoCurrentWindow = now % windowSizeMs;
        long remainingInWindow = windowSizeMs - timeIntoCurrentWindow;
        Duration retry = Duration.ofMillis(Math.max(100, remainingInWindow));

        log.debug("Calculated fixed window retry duration for key: {}: {}ms", key, retry.toMillis());
        return retry;
    }
}
