package com.notificationapp.ratelimiter.utils;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneOffset;

@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        // Fix initial instant to a clean epoch boundary
        return new MutableClock(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);
    }
}
