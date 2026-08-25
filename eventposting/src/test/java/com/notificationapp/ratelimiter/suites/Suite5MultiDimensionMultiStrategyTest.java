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
        "spring.config.import=optional:classpath:Config/combination-dimension-test.yml",
        "test.payload.path=payloads/sample-payload.json"
})
class Suite5MultiDimensionMultiStrategyTest extends BaseRateLimiterTest {

    @MockitoBean
    private Clock clock;

    private MutableClock mutableClock;

    @BeforeEach
    void setupClockAndStore() {
        mutableClock = new MutableClock(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);

        Mockito.when(clock.millis()).thenAnswer(invocation -> mutableClock.millis());
        Mockito.when(clock.instant()).thenAnswer(invocation -> mutableClock.instant());
        Mockito.when(clock.getZone()).thenAnswer(invocation -> mutableClock.getZone());
    }

    @Test
    @DisplayName("TC-05A: Multi-Dimension Hierarchical Enforcement (Global Token Bucket + IP Sliding Counter + User Fixed Window)")
    void testMultiDimensionMultiStrategyLimits() {
        log.info("=== STARTING TC-05A: Multi-Dimension Multi-Strategy Hierarchy Test ===");

        // User headers enabling IP and USER_ID dimension resolution
        Map<String, String> user1Headers = Map.of(
                "X-Forwarded-For", "192.168.1.100",
                "X-User-Id", "USER_101"
        );

        Map<String, String> user2SameIpHeaders = Map.of(
                "X-Forwarded-For", "192.168.1.100", // Same IP as USER_101
                "X-User-Id", "USER_102"             // Different User ID
        );

        // --- PHASE 1: USER_101 hits User-Tier Limit (3 requests) ---
        // Global limit = 10, IP limit = 5, User limit = 3 -> Expect 3 allowed, 2 rate-limited
        log.info("--- PHASE 1: Firing 5 requests for USER_101 (User limit = 3) ---");
        TestExecutionResult user1Result = executeSequentialRequests(5, user1Headers);
        user1Result.assertExactCounts(3, 2);

        // --- PHASE 2: USER_102 on SAME IP uses remaining IP capacity ---
        // IP 192.168.1.100 has consumed 3 requests already. Limit is 5 -> USER_102 should get 2 allowed requests
        log.info("--- PHASE 2: Firing 4 requests for USER_102 on same IP (IP remaining = 2) ---");
        TestExecutionResult user2Result = executeSequentialRequests(4, user2SameIpHeaders);
        user2Result.assertExactCounts(2, 2);

        // --- PHASE 3: Advance time into next window to reset limits ---
        log.info("--- Advancing clock by 1.1 seconds for window resets ---");
        mutableClock.advanceBy(Duration.ofMillis(1100));

        // --- PHASE 4: Post-reset request succeeds ---
        log.info("--- PHASE 3: Verifying recovery after window transition ---");
        TestExecutionResult recoveryResult = executeSequentialRequests(1, user1Headers);
        assertThat(recoveryResult.successCount()).isEqualTo(1);
    }
}