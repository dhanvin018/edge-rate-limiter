package com.notificationapp.ratelimiter.model;

import com.notificationapp.ratelimiter.strategy.LimitStrategy;

public record RateLimitCommitItem(
        String key,
        WindowRule rule,
        LimitStrategy strategy,
        int cost
) {}