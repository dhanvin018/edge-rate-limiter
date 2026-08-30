package com.notificationapp.ratelimiter.config;

public enum FallbackPolicy {
    FAIL_CLOSED, // Block requests
    FAIL_OPEN    // Allow requests through when rate limiter storage fails
}