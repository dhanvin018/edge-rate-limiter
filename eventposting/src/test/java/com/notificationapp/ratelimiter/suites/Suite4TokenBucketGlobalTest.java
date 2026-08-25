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
        "spring.config.import=optional:classpath:Config/token-bucket-global.yml",
        "test.payload.path=payloads/sample-payload.json"
})
class Suite4TokenBucketGlobalTest extends BaseRateLimiterTest {

    @MockitoBean
    private Clock clock;

    private MutableClock mutableClock;

    @BeforeEach
    void setupClock() {
        mutableClock = new MutableClock(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);

        Mockito.when(clock.millis()).thenAnswer(invocation -> mutableClock.millis());
        Mockito.when(clock.instant()).thenAnswer(invocation -> mutableClock.instant());
        Mockito.when(clock.getZone()).thenAnswer(invocation -> mutableClock.getZone());
    }

    @Test
    @DisplayName("TC-04A: Token Bucket Global - Single Window Burst Capacity Enforcement")
    void testTokenBucketCapacityEnforcement() {
        int capacity = 10;
        int totalRequests = 15; // Capacity is 10 -> Expect 10 Allowed, 5 Rate Limited

        log.info("=== STARTING TC-03A: Token Bucket Burst Capacity Enforcement ===");

        TestExecutionResult result = executeSequentialRequests(totalRequests, Map.of());

        // Asserts zero system errors, exactly 10 passed (2xx), and exactly 5 rate-limited (429)
        result.assertExactCounts(capacity, totalRequests - capacity);
    }

    @Test
    @DisplayName("TC-04B: Token Bucket - Refill & Local Leasing Capacity Recovery")
    void testTokenBucketRefillAndRecovery() {
        log.info("=== STARTING TC-03B: Token Bucket Refill & Capacity Recovery Test ===");

        // Phase 1: Exhaust full bucket capacity (10 allowed, 5 blocked)
        log.info("--- PHASE 1: Draining full bucket capacity (10 allowed, 5 blocked) ---");
        TestExecutionResult phase1 = executeSequentialRequests(15, Map.of());
        phase1.assertExactCounts(10, 5);

        // Verify immediately that request #16 is still blocked
        log.info("--- Verifying request is blocked immediately when bucket is empty ---");
        TestExecutionResult immediateBlockCheck = executeSequentialRequests(1, Map.of());
        assertThat(immediateBlockCheck.rateLimitedCount())
                .as("Request should be rate-limited when no tokens are available")
                .isEqualTo(1);

        // Phase 2: Advance clock by 1 second to trigger full bucket refill (10 tokens/sec)
        log.info("--- Advancing clock by 1.0 second to refill bucket ---");
        mutableClock.advanceBy(Duration.ofSeconds(1));

        // Phase 3: Fire 10 new requests; all should be allowed with the refilled capacity
        log.info("--- PHASE 2: Sending requests after token refill ---");
        TestExecutionResult phase2 = executeSequentialRequests(10, Map.of());

        // Assert 10 successes, 0 rate-limited, 0 system errors
        phase2.assertExactCounts(10, 0);
    }
}