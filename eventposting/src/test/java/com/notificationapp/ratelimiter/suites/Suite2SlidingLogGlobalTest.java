package com.notificationapp.ratelimiter.suites;

import com.notificationapp.ratelimiter.base.BaseRateLimiterTest;
import com.notificationapp.ratelimiter.utils.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "authentication.required=false",
        "spring.config.import=optional:classpath:Config/sliding-log-global.yml",
        "test.payload.path=payloads/sample-payload.json"
})
class Suite2SlidingLogGlobalTest extends BaseRateLimiterTest {

    // Spring overrides the production Clock bean with this Mockito mock
    @MockitoBean
    private Clock clock;

    // Standard Java field (managed in @BeforeEach)
    private MutableClock mutableClock;

    @BeforeEach
    void setupClock() {
        // Instantiate MutableClock directly at a fixed epoch boundary
        mutableClock = new MutableClock(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);

        // Delegate Spring-managed Mockito Clock calls to mutableClock
        Mockito.when(clock.millis()).thenAnswer(invocation -> mutableClock.millis());
        Mockito.when(clock.instant()).thenAnswer(invocation -> mutableClock.instant());
        Mockito.when(clock.getZone()).thenAnswer(invocation -> mutableClock.getZone());
    }

    @Test
    @DisplayName("TC-02A: Sliding Window Log Global - Single Window Capacity Enforcement")
    void testSlidingLogGlobalCapacity() {
        int windowLimit = 10;
        int totalRequests = 15; // Limit is 10 -> Expect 10 Allowed, 5 Rate Limited

        log.info("=== STARTING TC-02A: Sliding Log Global Capacity Enforcement ===");

        TestExecutionResult result = executeSequentialRequests(totalRequests, Map.of());

        // Asserts zero system errors, exactly 10 passed (2xx), and exactly 5 rate-limited (429)
        result.assertExactCounts(windowLimit, totalRequests - windowLimit);
    }

    @Test
    @DisplayName("TC-02B: Sliding Window Log - Old Request Expiry & Capacity Recovery")
    void testSlidingLogWindowExpiry() {
        log.info("=== STARTING TC-02B: Sliding Window Log Expiry Test ===");

        // Phase 1: Exhaust full capacity (10 allowed, 5 blocked)
        log.info("--- PHASE 1: Filling capacity (10 allowed, 5 blocked) ---");
        TestExecutionResult phase1 = executeSequentialRequests(15, Map.of());
        phase1.assertExactCounts(10, 5);

        // Verify immediately that request #16 is still blocked
        log.info("--- Verifying request is blocked immediately after maxing capacity ---");
        TestExecutionResult immediateBlockCheck = executeSequentialRequests(1, Map.of());
        assertThat(immediateBlockCheck.rateLimitedCount())
                .as("Request should be blocked while inside active window")
                .isEqualTo(1);

        // Phase 2: Advance clock by 2.1 seconds to evict all logs from the 2-second window
        log.info("--- Advancing clock by 2.1 seconds for sliding window logs to expire ---");
        mutableClock.advanceBy(Duration.ofMillis(2100));

        // Phase 3: Fire 10 new requests; all should be allowed now that old logs expired
        log.info("--- PHASE 2: Sending requests after window expiry ---");
        TestExecutionResult phase2 = executeSequentialRequests(10, Map.of());

        // Assert 10 successes, 0 rate-limited, 0 system errors
        phase2.assertExactCounts(10, 0);
    }
}