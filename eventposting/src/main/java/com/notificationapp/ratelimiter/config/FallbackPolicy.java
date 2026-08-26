package com.notificationapp.ratelimiter.config;

public enum FallbackPolicy {
    FAIL_CLOSED, // Block requests (503 Service Unavailable)
    FAIL_OPEN    // Allow requests through when rate limiter storage fails
}