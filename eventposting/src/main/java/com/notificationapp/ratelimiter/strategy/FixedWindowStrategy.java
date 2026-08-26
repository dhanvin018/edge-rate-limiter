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

    private final Clock clock;

    public FixedWindowStrategy(Clock clock) {
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

        log.debug("REQ -> Key: {} | Limit: '{}' | Window: '{}' | Cost: '{}' | Timestamp: '{}'",
                windowKey, rule.effectiveCapacity(), windowSizeMs, cost, Instant.now());

        boolean allowed = store.evaluateFixedWindowRead(windowKey, rule.effectiveCapacity(), cost);
        log.debug("Fixed window consumption result for key: {}: allowed = {}", windowKey, allowed);
        return allowed;
    }

    @Override
    public void commit(String key, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowSizeMs = rule.windowSize().toMillis();
        long currentWindowBucket = now / windowSizeMs;
        String windowKey = key + ":" + currentWindowBucket;

        log.debug("Committing fixed window strategy for windowKey: {}, cost: {}, windowSizeMs: {}",
                windowKey, cost, windowSizeMs);

        store.commitFixedWindowToPipeline(windowKey, cost, windowSizeMs);
    }

    @Override
    public boolean tryConsume(String key, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowSizeMs = rule.windowSize().toMillis();
        long currentWindowBucket = now / windowSizeMs;
        String windowKey = key + ":" + currentWindowBucket;

        log.debug("REQ -> Key: {} | Limit: '{}' | Window: '{}' | Cost: '{}' | Timestamp: '{}'",
                windowKey, rule.effectiveCapacity(), windowSizeMs, cost, Instant.now());

        boolean allowed = store.evaluateFixedWindowAtomic(windowKey, rule.effectiveCapacity(), windowSizeMs, cost);
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
