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

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> slidingWindowLogScript;
    private final RedisScript<Long> slidingWindowLogReadScript;
    private final RedisScript<Long> slidingWindowLogWriteScript;
    private final Clock clock;

    public SlidingWindowLogStrategy(StringRedisTemplate redisTemplate,
                                    @Qualifier("slidingWindowLogScript") RedisScript<Long> slidingWindowLogScript,
                                    @Qualifier("slidingWindowLogReadScript") RedisScript<Long> slidingWindowLogReadScript,
                                    @Qualifier("slidingWindowLogWriteScript") RedisScript<Long> slidingWindowLogWriteScript,
                                    Clock clock) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowLogScript = slidingWindowLogScript;
        this.slidingWindowLogReadScript = slidingWindowLogReadScript;
        this.slidingWindowLogWriteScript = slidingWindowLogWriteScript;
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

        Long result = 0L;
        try {
            result = redisTemplate.execute(
                    slidingWindowLogReadScript,
                    List.of(key),                             // KEYS[1]
                    String.valueOf(rule.effectiveCapacity()), // ARGV[1]
                    String.valueOf(windowMs),                 // ARGV[2]
                    String.valueOf(now)                       // ARGV[3]
            );
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable while evaluating rate limit for key: {}. Failing closed.", key, e);
            return false;
        }

        boolean allowed = result != null && result == 1L;
        log.debug("Sliding window log inspection result for key {}: allowed = {}", key, allowed);
        return allowed;
    }

    @Override
    public void commit(String key, WindowRule rule, int cost, RateLimitStore store, RedisConnection connection) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();
        String uniqueMemberId = now + ":" + UUID.randomUUID().toString();

        log.debug("Committing sliding window log state for key: {}, memberId: {}", key, uniqueMemberId);

        // Enqueue write script onto low-level Redis connection for pipelined execution
        connection.evalSha(
                slidingWindowLogWriteScript.getSha1(),
                ReturnType.INTEGER,
                1, // Number of KEYS
                key.getBytes(StandardCharsets.UTF_8),                     // KEYS[1]
                String.valueOf(windowMs).getBytes(StandardCharsets.UTF_8),// ARGV[1]
                String.valueOf(now).getBytes(StandardCharsets.UTF_8),     // ARGV[2]
                uniqueMemberId.getBytes(StandardCharsets.UTF_8)          // ARGV[3]
        );
    }

    @Override
    public boolean tryConsume(String key, WindowRule rule, int cost, RateLimitStore store) {
        long now = clock.millis();
        long windowMs = rule.windowSize().toMillis();
        long currentWindowBucket = now / windowMs;
        long previousWindowBucket = currentWindowBucket - 1;

        String currentKey = key + ":" + currentWindowBucket;
        String previousKey = key + ":" + previousWindowBucket;

        log.debug("Evaluating sliding window log strategy for key: {}, previousKey: {}, cost: {}, capacity: {}",
                currentKey, previousKey, cost, rule.effectiveCapacity());

        String uniqueMemberId = now + ":" + java.util.UUID.randomUUID().toString();

        Long result = 0L;
        try {
            result = redisTemplate.execute(
                    slidingWindowLogScript,
                    List.of(key),                                  // KEYS[1]
                    String.valueOf(rule.effectiveCapacity()),      // ARGV[1]
                    String.valueOf(windowMs),                     // ARGV[2]
                    String.valueOf(now),                          // ARGV[3]
                    uniqueMemberId                                // ARGV[4] -> MUST BE UNIQUE PER REQUEST
            );
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable while evaluating rate limit for key: {}. Failing closed.", key, e);
        }

        boolean allowed = result != null && result == 1L;
        log.debug("Sliding window log consumption result for key: {}: allowed = {}", currentKey, allowed);
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
