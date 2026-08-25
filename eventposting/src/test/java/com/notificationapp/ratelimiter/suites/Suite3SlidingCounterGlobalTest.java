package com.notificationapp.ratelimiter.suites;

import com.notificationapp.ratelimiter.base.BaseRateLimiterTest;
import com.notificationapp.ratelimiter.utils.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "authentication.required=false",
        "spring.config.import=optional:classpath:Config/sliding-counter-global.yml",
        "test.payload.path=payloads/sample-payload.json"
})
class Suite3SlidingCounterGlobalTest extends BaseRateLimiterTest {

    @MockitoBean
    private Clock clock;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // Standard Java field (NO @Autowired)
    private MutableClock mutableClock;

    @BeforeEach
    void setupClock() {
        // 1. Instantiate MutableClock directly
        mutableClock = new MutableClock(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);

        // 2. Delegate the Spring-managed Mockito Clock calls to mutableClock
        Mockito.when(clock.millis()).thenAnswer(invocation -> mutableClock.millis());
        Mockito.when(clock.instant()).thenAnswer(invocation -> mutableClock.instant());
        Mockito.when(clock.getZone()).thenAnswer(invocation -> mutableClock.getZone());
    }


    @Test
    @DisplayName("TC-03A: Sliding Window Counter - Single Window Capacity Enforcement")
    void testSlidingCounterSingleWindowCapacity() {
        log.info("=== STARTING TC-03A: Single Window Capacity Test ===");

        TestExecutionResult result = executeSequentialRequests(15, Map.of());
        result.assertExactCounts(10, 5);

        logRedisCounterState("End of TC-03A");
    }

    @Test
    @DisplayName("TC-03B: Sliding Window Counter - Deterministic Multi-Window Decay Analysis")
    void testSlidingCounterCrossWindowRecovery() {
        log.info("=== STARTING TC-03B: Deterministic Cross-Window Recovery Test ===");

        long windowMs = 10000; // 10s window

        // Phase 1: Exhaust capacity
        log.info("--- PHASE 1: Exhausting 10s window bucket capacity ---");
        TestExecutionResult phase1 = executeSequentialRequests(15, Map.of());
        phase1.assertExactCounts(10, 5);

        logRedisCounterState("IMMEDIATELY AFTER PHASE 1");

        // Align clock to 50% into next bucket
        long now = mutableClock.millis();
        long timeIntoCurrentBucket = now % windowMs;
        long millisToNextBucket = windowMs - timeIntoCurrentBucket;
        long targetOffsetInNextBucket = 5000;
        long advanceDuration = millisToNextBucket + targetOffsetInNextBucket;

        log.info("--- Advancing clock by {} ms to land exactly 50% into next bucket ---", advanceDuration);
        mutableClock.advanceBy(Duration.ofMillis(advanceDuration));

        logRedisCounterState("BEFORE PHASE 2 (EXACTLY 50% INTO NEXT BUCKET)");

        // Phase 2: Send 5 requests
        log.info("--- PHASE 2: Sending 5 requests (Expect all 5 allowed) ---");
        TestExecutionResult phase2 = executeSequentialRequests(5, Map.of());
        phase2.assertExactCounts(5, 0);

        logRedisCounterState("AFTER PHASE 2 EXECUTION");

        // Boundary check
        log.info("--- Verifying capacity ceiling: Sending 1 extra request (Expect Rate Limited) ---");
        TestExecutionResult boundaryCheck = executeSequentialRequests(1, Map.of());
        assertThat(boundaryCheck.rateLimitedCount())
                .as("Request breaching the 50% weighted limit must be rate limited")
                .isEqualTo(1);

        // Phase 3: Full window decay (11s)
        log.info("--- Advancing clock by 20 seconds for full capacity reset ---");
        mutableClock.advanceBy(Duration.ofSeconds(20));

        logRedisCounterState("BEFORE PHASE 3 (FULL RESET)");

        log.info("--- PHASE 3: Testing full capacity reset ---");
        TestExecutionResult phase3 = executeSequentialRequests(10, Map.of());
        phase3.assertExactCounts(10, 0);

        logRedisCounterState("FINAL STATE AT END OF TC-03B");
    }

    private void logRedisCounterState(String stageLabel) {
        log.info("================ REDIS STATE DIAGNOSTIC [{}] ================", stageLabel);
        var keys = stringRedisTemplate.keys("rl:*");
        if (keys == null || keys.isEmpty()) {
            log.info("  No keys found in Redis matching pattern 'rl:*'");
        } else {
            for (String key : keys) {
                String val = stringRedisTemplate.opsForValue().get(key);
                Long ttl = stringRedisTemplate.getExpire(key);
                log.info("  Key: [{}] | Value: [{}] | TTL: [{}s]", key, val, ttl);
            }
        }
        log.info("==========================================================================");
    }
}