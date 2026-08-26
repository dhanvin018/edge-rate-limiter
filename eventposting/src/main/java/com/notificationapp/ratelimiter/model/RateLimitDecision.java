package com.notificationapp.ratelimiter.model;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

public record RateLimitDecision(
        boolean allowed,
        DenyReason reason,
        String violatedRule,
        Duration retryAfter,
        String userMessage,
        List<RedisCommit> commitItems
) {

    //TODO: Refine these methods
    public static RateLimitDecision allow(){
        return new RateLimitDecision(true,
                null,
                null,
                null,
                null,
                Collections.emptyList());
    }
    public static RateLimitDecision rateExceeded(
            String rule,
            Duration retryAfter,
            String message){
        return new RateLimitDecision(false,
                DenyReason.RATE_EXCEEDED,
                "rateExceeded",
                null,
                "429: Payloads exceeded rate, try soon",
                Collections.emptyList());
    }
    public static RateLimitDecision costExceedsCapacity(String rule, String message){
        return new RateLimitDecision(false,
                DenyReason.COST_EXCEEDED,
                "costExceeded",
                null,
                "413: Payload Too Large",
                Collections.emptyList());

    }
    public static RateLimitDecision allowed(List<RedisCommit> commitItems) {
        return new RateLimitDecision(true, null, null, Duration.ZERO, null, commitItems);
    }

    public static RateLimitDecision denied(DenyReason reason, String rule, Duration retryAfter) {
        return new RateLimitDecision(false, reason, rule, retryAfter, null, Collections.emptyList());
    }

}
