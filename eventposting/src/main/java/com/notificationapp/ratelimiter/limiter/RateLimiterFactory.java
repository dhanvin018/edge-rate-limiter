package com.notificationapp.ratelimiter.limiter;

import com.notificationapp.ratelimiter.config.RateLimitProperties;
import com.notificationapp.ratelimiter.model.Dimension;
import com.notificationapp.ratelimiter.config.RateLimitConfigProvider;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import com.notificationapp.ratelimiter.strategy.StrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RateLimiterFactory {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterFactory.class);

    private final RateLimitConfigProvider configProvider;
    private final RateLimitStore store;
    private final StrategyRegistry strategyRegistry;
    private final StringRedisTemplate redisTemplate;

    public RateLimiterFactory(RateLimitConfigProvider configProvider,
                              RateLimitStore store,
                              StrategyRegistry strategyRegistry,
                              StringRedisTemplate redisTemplate) {
        this.configProvider = configProvider;
        this.store = store;
        this.strategyRegistry = strategyRegistry;
        this.redisTemplate = redisTemplate;
    }

    public CompositeRateLimiter createCompositeLimiter(RateLimitProperties props) {
        log.debug("Creating CompositeRateLimiter with dimension order: {}", props.getDimensionOrder());

        List<DimensionLimiter> limiters = props.getDimensionOrder().stream()
                .map(this::createDimensionLimiter)
                .toList();

        log.debug("Successfully created CompositeRateLimiter with {} dimension limiter(s)", limiters.size());
        return new CompositeRateLimiter(limiters, redisTemplate, store);
    }

    private DimensionLimiter createDimensionLimiter(Dimension dimension) {
        log.debug("Creating DimensionLimiter for dimension: {}", dimension);
        return new DimensionLimiter(dimension, configProvider, store, strategyRegistry);
    }
}
