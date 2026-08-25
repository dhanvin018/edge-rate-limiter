package com.notificationapp.ratelimiter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Arrays;

public enum LimitStrategyType {
    FIXED_WINDOW,
    SLIDING_WINDOW_LOG,
    SLIDING_WINDOW_COUNTER,
    TOKEN_BUCKET;

    @JsonCreator
    public static LimitStrategyType fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace("-", "_").toUpperCase();
        return Arrays.stream(LimitStrategyType.values())
                .filter(strategy -> strategy.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown strategy type: '" + value + "'. Supported: " + Arrays.toString(values())
                ));
    }
}