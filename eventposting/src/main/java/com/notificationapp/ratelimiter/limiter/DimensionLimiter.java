package com.notificationapp.ratelimiter.limiter;

import com.notificationapp.ratelimiter.config.RateLimitConfigProvider;
import com.notificationapp.ratelimiter.model.*;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import com.notificationapp.ratelimiter.strategy.LimitStrategy;
import com.notificationapp.ratelimiter.strategy.StrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.isNull;

public class DimensionLimiter {

    private static final Logger log = LoggerFactory.getLogger(DimensionLimiter.class);

    private final Dimension dimension;
    private final RateLimitConfigProvider rateLimiterConfigProvider;
    private final RateLimitStore store;
    private final StrategyRegistry strategyRegistry;

    DimensionLimiter(Dimension dimension,
                     RateLimitConfigProvider configProvider,
                     RateLimitStore store,
                     StrategyRegistry strategyRegistry) {
        this.dimension = dimension;
        this.rateLimiterConfigProvider = configProvider;
        this.store = store;
        this.strategyRegistry = strategyRegistry;
    }

    RateLimitDecision evaluate(RateLimitContext context) {
        log.debug("Evaluating rate limit for dimension: {} with context: {}", dimension, context);

        if (dimension == Dimension.USER_ID && context.userId() == null) {
            log.debug("Skipping USER_ID dimension rate limit check due to missing user ID");
            return RateLimitDecision.allow();
        }

        if (dimension == Dimension.IP_ADDRESS && context.ipAddress() == null) {
            log.debug("Skipping IP_ADDRESS dimension rate limit check due to missing IP address");
            return RateLimitDecision.allow();
        }

        List<WindowRule> rules = rateLimiterConfigProvider.rulesFor(context.userTier(), dimension);
        if (isNull(rules) || rules.isEmpty()) {
            log.debug("No rules found for dimension {}", dimension);
            return RateLimitDecision.allow();
        }

        String identity = resolveKey(dimension, context);
        log.debug("Rule count found for dimension {} : {}", dimension, rules.size());

        List<RedisCommitModel> commitItems = new ArrayList<>();

        for (WindowRule rule : rules) {
            String ruleId = dimension + ":" + rule.strategyType() + ":" + rule.windowSize() + ":" + rule.effectiveCapacity();

            if (context.cost() > rule.effectiveCapacity()) {
                log.debug("Request cost ({}) exceeds rule capacity ({}) for rule: {}",
                        context.cost(), rule.effectiveCapacity(), ruleId);
                release(commitItems);
                return RateLimitDecision.costExceedsCapacity(ruleId,
                        "Request exceeds maximum allowed batch size (" + rule.effectiveCapacity() + ") for " + dimension.name().toLowerCase() + ".");
            }

            LimitStrategy strategy = strategyRegistry.get(rule.strategyType());
            String key = identity + ":" + rule.strategyType().name() + ":" + rule.windowSize();

            if (!strategy.canConsume(key, rule, context.cost(), store)) {
                release(commitItems);
                Duration retry = strategy.retryAfter(key, rule, store);
                log.debug("Rate limit exceeded during inspect for key: {} on rule: {}. Retry after: {}s", key, ruleId, retry.toSeconds());
                return RateLimitDecision.rateExceeded(ruleId, retry,
                        "Rate limit exceeded for " + dimension.name().toLowerCase() + ". Try again in " + retry.toSeconds() + "s.");
            }

            commitItems.add(new RedisCommitModel(key, rule, strategy, context.cost()));
        }

        log.debug("Rate limit inspect passed for dimension: {}", dimension);
        return RateLimitDecision.allowed(commitItems);
    }

    // Returns reservations made by earlier rules in this dimension when a later rule rejects
    private void release(List<RedisCommitModel> passedRules) {
        for (RedisCommitModel item : passedRules) {
            item.strategy().release(item.key(), item.rule(), item.cost(), store);
        }
    }

    private String resolveKey(Dimension dimension, RateLimitContext ctx) {
        String identity = switch (dimension) {
            case USER_ID -> ctx.userId();
            case IP_ADDRESS -> ctx.ipAddress();
            case GLOBAL -> "*";   // one shared bucket, no per-entity identity to key on
        };
        return "rl:" + dimension.name().toLowerCase() + ":" + identity;
    }
}
