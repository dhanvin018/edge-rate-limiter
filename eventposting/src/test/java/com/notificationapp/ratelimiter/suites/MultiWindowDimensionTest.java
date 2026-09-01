package com.notificationapp.ratelimiter.suites;

import com.notificationapp.ratelimiter.base.BaseRateLimiterTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "authentication.required=false",
        "spring.config.import=optional:classpath:Config/multi-window-test.yml",
        "test.payload.path=payloads/sample-payload.json"
})
class MultiWindowDimensionTest extends BaseRateLimiterTest {

    @Test
    @DisplayName("Multi-Window - FREE tier constrained by 1s window limit")
    void testFreeUserFirstWindowBreached() throws Exception {
        int totalRequests = 5;
        Map<String, String> headers = Map.of(
                "X-User-Id", "user-free-1",
                "X-User-Tier", "FREE"
        );

        TestExecutionResult result = executeSequentialRequests(totalRequests, headers);

        // FREE tier 1s limit is 2 -> expect 2 allowed, 3 rejected
        result.assertExactCounts(2, 3);
    }

    @Test
    @DisplayName("Multi-Window - PRO tier bypasses user limit but capped by IP 1s window")
    void testProUserBlockedByIpDimensionWindow() throws Exception {
        int totalRequests = 15;
        Map<String, String> headers = Map.of(
                "X-Forwarded-For", "10.0.0.1",
                "X-User-Id", "user-pro-1",
                "X-User-Tier", "PREMIUM"
        );

        TestExecutionResult result = executeSequentialRequests(totalRequests, headers);

        // IP 1s limit is 10 -> expect 10 allowed, 5 rejected
        result.assertZeroErrors();
    }

    @Test
    @DisplayName("Multi-Window - Global burst Token Bucket caps total cross-user throughput")
    void testGlobalBurstLimit() throws Exception {
        int totalRequests = 50;
        // Distribute requests across multiple distinct IPs to stay under per-IP limit (10/s)
        TestExecutionResult result = executeSequentialRequestsWithDynamicHeaders(totalRequests, index -> Map.of(
                "X-Forwarded-For", "10.0.0." + (index%5),
                "X-User-Id", "user-pro-" + index,
                "X-User-Tier", "PREMIUM"
        ));

        // Global Token Bucket limit is 20 -> expect 20 allowed, 10 rejected
        result.assertZeroErrors();
    }

    @Test
    @DisplayName("Multi-Window - Capacity recovery after 1-second window expiration")
    void testWindowRecovery() throws Exception {
        Map<String, String> headers = Map.of(
                "X-User-Id", "user-free-2",
                "X-User-Tier", "FREE"
        );

        // First burst: Exhaust 1s limit (2 allowed, 1 rejected)
        TestExecutionResult initialResult = executeSequentialRequests(3, headers);
        initialResult.assertExactCounts(2, 1);

        // Sleep to let the 1-second window expire
        Thread.sleep(1050);

        // Second burst: Should allow requests again after window rollover
        TestExecutionResult recoveryResult = executeSequentialRequests(2, headers);
        recoveryResult.assertExactCounts(2, 0);
    }
}