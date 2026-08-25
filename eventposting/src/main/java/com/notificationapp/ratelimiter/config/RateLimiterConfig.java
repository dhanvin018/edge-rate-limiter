package com.notificationapp.ratelimiter.config;

import com.notificationapp.ratelimiter.limiter.CompositeRateLimiter;
import com.notificationapp.ratelimiter.limiter.RateLimiterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class RateLimiterConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterConfig.class);

    @Bean
    public CompositeRateLimiter compositeRateLimiter(
            RateLimitProperties props,
            RateLimiterFactory factory
    ) {
        log.info("Initializing CompositeRateLimiter bean");
        CompositeRateLimiter compositeRateLimiter = factory.createCompositeLimiter(props);
        log.debug("Successfully created CompositeRateLimiter instance");
        return compositeRateLimiter;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
