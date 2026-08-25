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
     * Phase 1: Read-Only Inspection.
     * Evaluates whether the request fits within capacity using the strategy's read Lua script.
     * Does NOT mutate Redis state.
     */
    boolean canConsume(String key, WindowRule rule, int cost, RateLimitStore store);

    /**
     * Phase 2: State Mutation / Batch Commit.
     * Enqueues the strategy's write Lua script onto an active Redis pipeline connection.
     * Executed only when ALL evaluated dimensions pass Phase 1.
     */
    void commit(String key, WindowRule rule, int cost, RateLimitStore store, RedisConnection connection);

    boolean tryConsume(String key, WindowRule rule, int cost, RateLimitStore store);
    Duration retryAfter(String key, WindowRule rule, RateLimitStore store);
}
