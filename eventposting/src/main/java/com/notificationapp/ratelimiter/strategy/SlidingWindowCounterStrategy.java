package com.notificationapp.ratelimiter.strategy;

import com.notificationapp.ratelimiter.model.LimitStrategyType;
import com.notificationapp.ratelimiter.model.RateLimitContext;
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

@Component
public  class SlidingWindowCounterStrategy implements LimitStrategy {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowCounterStrategy.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> slidingWindowCounterScript;
    private final RedisScript<Long> slidingWindowCounterReadScript;
    private final RedisScript<Long> slidingWindowCounterWriteScript;
    private final Clock clock;

    public SlidingWindowCounterStrategy(StringRedisTemplate redisTemplate,
                                        @Qualifier("slidingWindowCounterScript") RedisScript<Long> slidingWindowCounterScript,
                                        @Qualifier("slidingWindowCounterReadScript") RedisScript<Long> slidingWindowCounterReadScript,
                                        @Qualifier("slidingWindowCounterWriteScript") RedisScript<Long> slidingWindowCounterWriteScript,
                                        Clock clock) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowCounterScript = slidingWindowCounterScript;
        this.slidingWindowCounterReadScript = slidingWindowCounterReadScript;
        this.slidingWindowCounterWriteScript = slidingWindowCounterWriteScript;
        this.clock = clock;
    }

    @Override
    public LimitStrategyType type() {
        return LimitStrategyType.SLIDING_WINDOW_COUNTER;
    }


    @Override
    public boolean canConsume(String identityKey, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();

        long currentWindowBucket = now / windowMs;
        long previousWindowBucket = currentWindowBucket - 1;

        String currentKey = identityKey + ":" + currentWindowBucket;
        String previousKey = identityKey + ":" + previousWindowBucket;

        log.debug("Evaluating sliding window counter for key: {}, current: {}, previous: {}",
                identityKey, currentKey, previousKey);

        Long result = 0L;
        try {
            result = redisTemplate.execute(
                    slidingWindowCounterReadScript,
                    List.of(currentKey, previousKey),
                    String.valueOf(rule.effectiveCapacity()),
                    String.valueOf(windowMs),
                    String.valueOf(now),
                    String.valueOf(cost)
            );
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable while evaluating rate limit for key: {}. Failing closed.", identityKey, e);
            return false;
        }

        boolean allowed = result != null && result == 1L;
        log.debug("Sliding window counter result for key {}: allowed = {}", identityKey, allowed);
        return allowed;
    }

    @Override
    public void commit(String identityKey, WindowRule rule, int cost, RateLimitStore store, RedisConnection connection) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();

        long currentWindowBucket = now / windowMs;
        String currentKey = identityKey + ":" + currentWindowBucket;

        log.debug("Committing sliding window counter state for key: {}, currentKey: {}, cost: {}, windowMs: {}",
                identityKey, currentKey, cost, windowMs);

        // Enqueue write script onto the low-level Redis connection for pipelined execution
        connection.evalSha(
                slidingWindowCounterWriteScript.getSha1(),
                ReturnType.INTEGER,
                1, // Number of KEYS
                currentKey.getBytes(StandardCharsets.UTF_8),                // KEYS[1]
                String.valueOf(cost).getBytes(StandardCharsets.UTF_8),       // ARGV[1]
                String.valueOf(windowMs).getBytes(StandardCharsets.UTF_8)   // ARGV[2]
        );
    }

    @Override
    public boolean tryConsume(String identityKey, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();

        // Bucket index based on epoch window boundary
        long currentWindowBucket = now / windowMs;
        long previousWindowBucket = currentWindowBucket - 1;

        String currentKey = identityKey + ":" + currentWindowBucket;
        String previousKey = identityKey + ":" + previousWindowBucket;

        log.debug("Evaluating sliding window counter for key: {}, current: {}, previous: {}",
                identityKey, currentKey, previousKey);


        Long result = 0L;
        try {
            result = redisTemplate.execute(
                    slidingWindowCounterScript,
                    List.of(currentKey, previousKey),
                    String.valueOf(rule.effectiveCapacity()),
                    String.valueOf(windowMs),
                    String.valueOf(now),
                    String.valueOf(cost)
            );
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable while evaluating rate limit for key: {}. Failing closed.", identityKey, e);
            return false; // Fail closed
        }

        boolean allowed = result != null && result == 1L;
        log.debug("Sliding window counter result for key {}: allowed = {}", identityKey, allowed);
        return allowed;
    }

    @Override
    public Duration retryAfter(String key, WindowRule rule, RateLimitStore store) {
        long now = System.currentTimeMillis();
        long windowMs = rule.windowSize().toMillis();
        long timeIntoWindow = now % windowMs;

        // Wait until the current window rolls over enough to drop below capacity
        long remainingInWindow = windowMs - timeIntoWindow;
        Duration retry = Duration.ofMillis(Math.max(100, remainingInWindow));

        log.debug("Calculated sliding window counter retry duration for key: {}: {}ms", key, retry.toMillis());
        return retry;
    }
}
