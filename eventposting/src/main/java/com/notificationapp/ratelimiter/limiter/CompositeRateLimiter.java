package com.notificationapp.ratelimiter.limiter;

import com.notificationapp.ratelimiter.model.RateLimitContext;
import com.notificationapp.ratelimiter.model.RateLimitDecision;
import com.notificationapp.ratelimiter.model.RedisCommitModel;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

public class CompositeRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CompositeRateLimiter.class);

    private final List<DimensionLimiter> orderedLimiters;
    private final RateLimitStore store;

    public CompositeRateLimiter(List<DimensionLimiter> orderedLimiters,
                                RateLimitStore store) {
        this.orderedLimiters = orderedLimiters;
        this.store = store;
    }

    // =========================================================================
    // PHASE 1: CHECK ACROSS ALL DIMENSIONS
    // =========================================================================
    @Override
    public RateLimitDecision evaluate(RateLimitContext context) {
        log.debug("Evaluating composite rate limits for context: {}", context);

        List<RedisCommitModel> aggregatedCommitItems = new ArrayList<>();

        for (DimensionLimiter limiter : orderedLimiters) {
            RateLimitDecision decision = limiter.evaluate(context);

            if (!decision.allowed()) {
                log.debug("Rate limit exceeded during evaluation for context: {}. Decision: {}", context, decision);
                // Return reservations made by dimensions that already passed
                for (RedisCommitModel item : aggregatedCommitItems) {
                    item.strategy().release(item.key(), item.rule(), item.cost(), store);
                }
                return decision;
            }

            // Collect items eligible for commit from this dimension
            if (decision.commitItems() != null && !decision.commitItems().isEmpty()) {
                aggregatedCommitItems.addAll(decision.commitItems());
            }
        }

        log.debug("All composite rate limit checks passed for context: {}. Total commit items: {}",
                context, aggregatedCommitItems.size());

        return RateLimitDecision.allowed(aggregatedCommitItems);
    }

    // =========================================================================
    // PHASE 2: ATOMIC PIPELINED COMMIT
    // =========================================================================
    @Override
    public boolean commit(List<RedisCommitModel> commitItems) {
        return store.executePipeline(commitItems);
    }
}