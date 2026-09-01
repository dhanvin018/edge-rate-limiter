package com.notificationapp.ratelimiter.limiter;

import com.notificationapp.ratelimiter.model.RateLimitContext;
import com.notificationapp.ratelimiter.model.RateLimitDecision;
import com.notificationapp.ratelimiter.model.RedisCommitModel;

public interface RateLimiter {
    RateLimitDecision evaluate(RateLimitContext context);
    boolean commit(java.util.List<RedisCommitModel> commitItems);
}
