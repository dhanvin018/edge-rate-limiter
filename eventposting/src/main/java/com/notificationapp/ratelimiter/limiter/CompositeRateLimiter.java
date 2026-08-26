package com.notificationapp.ratelimiter.limiter;

import com.notificationapp.ratelimiter.model.RedisCommit;
import com.notificationapp.ratelimiter.model.RateLimitContext;
import com.notificationapp.ratelimiter.model.RateLimitDecision;
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
    private final StringRedisTemplate redisTemplate;
    private final RateLimitStore store;

    public CompositeRateLimiter(List<DimensionLimiter> orderedLimiters,
                                StringRedisTemplate redisTemplate,
                                RateLimitStore store) {
        this.orderedLimiters = orderedLimiters;
        this.redisTemplate = redisTemplate;
        this.store = store;
    }

    // =========================================================================
    // PHASE 1: READ-ONLY INSPECTION ACROSS ALL DIMENSIONS
    // =========================================================================
    @Override
    public RateLimitDecision evaluate(RateLimitContext context) {
        log.debug("Evaluating composite rate limits for context: {}", context);

        List<RedisCommit> aggregatedCommitItems = new ArrayList<>();

        for (DimensionLimiter limiter : orderedLimiters) {
            RateLimitDecision decision = limiter.evaluate(context);

            if (!decision.allowed()) {
                log.debug("Rate limit exceeded during evaluation for context: {}. Decision: {}", context, decision);
                // Short-circuit immediately: zero state has been modified in Redis up to this point
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
    public boolean commit(List<RedisCommit> commitItems) {
        if (commitItems == null || commitItems.isEmpty()) {
            log.trace("No items to commit to Redis.");
            return true;
        }

        log.debug("Starting Phase 2 pipelined commit for {} item(s)", commitItems.size());

        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (RedisCommit item : commitItems) {
                    log.trace("Enqueuing write script for key [{}] via strategy [{}]",
                            item.key(), item.strategy().type());

                    item.strategy().commit(
                            item.key(),
                            item.rule(),
                            item.cost(),
                            store,
                            connection
                    );
                }
                return null; // Return null required by executePipelined contract
            });

            log.debug("Successfully flushed Phase 2 commit pipeline across all dimensions.");
            return true;

        } catch (Exception e) {
            // Log the failure clearly with context
            log.error("Failed to commit rate limit state to Redis during Phase 2 pipeline. " +
                    "Applying Fallback Strategy (Fail Closed / Reject). Error: {}", e.getMessage(), e);

            // Return false to signal that the commit failed due to system/datastore error
            return false;
        }
    }
}