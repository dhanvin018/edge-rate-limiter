package com.notificationapp.ratelimiter.model;

import java.time.Duration;

public record WindowRule(
        Dimension dimension,
        Duration windowSize,
        long limit,
        LimitStrategyType strategyType,
        Long burstCapacity,
        LocalBatchConfig localBatch
) {
    public WindowRule(Dimension dimension, Duration windowSize, long limit, LimitStrategyType strategyType, Long burstCapacity) {
        this(dimension, windowSize, limit, strategyType, burstCapacity, LocalBatchConfig.disabled());
    }
    public long effectiveCapacity(){
        return burstCapacity!=null?burstCapacity:limit;
    }
}
