package com.notificationapp.ratelimiter.model;

public record LocalBatchConfig(
        boolean enabled,
        int batchSize,
        int refillThreshold
) {
    public static LocalBatchConfig disabled() {
        return new LocalBatchConfig(false, 0, 0);
    }
}