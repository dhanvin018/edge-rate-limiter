package com.notificationapp.ratelimiter.strategy;

import com.notificationapp.ratelimiter.model.LimitStrategyType;
import com.notificationapp.ratelimiter.model.RateLimitContext;
import com.notificationapp.ratelimiter.model.WindowRule;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import org.springframework.data.redis.connection.RedisConnection;

import java.time.Duration;

public interface LimitStrategy {
    LimitStrategyType type();

    /**
     * Phase 1: Check.
     * Evaluates whether the request fits within capacity. Most strategies only read Redis here.
     * A token bucket with local batching reserves tokens in memory, and may lease a batch from Redis.
     * Any reservation must be undone with {@link #release} if the request is rejected later.
     */
    boolean canConsume(String key, WindowRule rule, int cost, RateLimitStore store);

    /**
     * Phase 2: Commit.
     * Records the request using the strategy's write Lua script.
     * Executed only when ALL evaluated dimensions pass Phase 1.
     */
    void commit(String key, WindowRule rule, int cost, RateLimitStore store);

    /**
     * Undoes a reservation made by {@link #canConsume} when a later rule or dimension rejects the request.
     * Strategies that reserve nothing in Phase 1 need no action.
     */
    default void release(String key, WindowRule rule, int cost, RateLimitStore store) {
    }

    boolean tryConsume(String key, WindowRule rule, int cost, RateLimitStore store);
    Duration retryAfter(String key, WindowRule rule, RateLimitStore store);
}
