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
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class SlidingWindowLogStrategy implements LimitStrategy {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowLogStrategy.class);

    private final Clock clock;

    public SlidingWindowLogStrategy(Clock clock) {
        this.clock = clock;
    }

    @Override
    public LimitStrategyType type() {
        return LimitStrategyType.SLIDING_WINDOW_LOG;
    }

    @Override
    public boolean canConsume(String key, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();

        log.debug("Evaluating sliding window log strategy for key: {}, cost: {}, capacity: {}",
                key, cost, rule.effectiveCapacity());

        boolean allowed = store.evaluateSlidingWindowLogRead(key, rule.effectiveCapacity(), windowMs, now);
        log.debug("Sliding window log inspection result for key {}: allowed = {}", key, allowed);
        return allowed;
    }

    @Override
    public void commit(String key, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();
        String uniqueMemberId = now + ":" + UUID.randomUUID().toString();

        log.debug("Committing sliding window log state for key: {}, memberId: {}", key, uniqueMemberId);


        store.commitSlidingWindowLogToPipeline(key, windowMs, now, uniqueMemberId);
    }

    @Override
    public boolean tryConsume(String key, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();

        log.debug("Evaluating sliding window log atomic strategy for key: {}, cost: {}, capacity: {}",
                key, cost, rule.effectiveCapacity());

        String uniqueMemberId = now + ":" + UUID.randomUUID().toString();

        boolean allowed = store.evaluateSlidingWindowLogAtomic(key, rule.effectiveCapacity(), windowMs, now, uniqueMemberId);
        log.debug("Sliding window log consumption result for key: {}: allowed = {}", key, allowed);
        return allowed;
    }

    @Override
    public Duration retryAfter(String key, WindowRule rule, RateLimitStore store) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();
        long timeIntoWindow = now % windowMs;

        // Wait until the current window rolls over enough to drop below capacity
        long remainingInWindow = windowMs - timeIntoWindow;
        log.debug("Calculated fixed window retry duration for key: {}: {}ms", key, remainingInWindow);
        return Duration.ofMillis(Math.max(100, remainingInWindow));
    }
}
