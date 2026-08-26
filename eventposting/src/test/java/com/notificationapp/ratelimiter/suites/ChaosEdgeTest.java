package com.notificationapp.ratelimiter.suites;

import com.notificationapp.ratelimiter.base.BaseRateLimiterTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "authentication.required=false",
        "spring.config.import=optional:classpath:Config/edge-case-setup.yml",
        "test.payload.path=payloads/sample-payload.json"
})
class ChaosEdgeTest extends BaseRateLimiterTest {

    @Test
    @DisplayName("Inverted evaluation hierarchy enforces USER_ID 100ms micro-window first")
    void testInvertedOrderUserFirst() throws Exception {
        int totalRequests = 3;
        Map<String, String> headers = Map.of(
                "X-User-Id", "guest-user-1",
                "X-User-Tier", "FREE"
        );

        TestExecutionResult result = executeSequentialRequests(totalRequests, headers);

        // FREE tier limit is 1 per 100ms -> expect 1 allowed, 2 rejected
        result.assertZeroErrors();
    }

    @Test
    @DisplayName("Non-batched Token Bucket at IP level strictly enforces limit")
    void testNonBatchedIpTokenBucket() throws Exception {
        int totalRequests = 12;
        Map<String, String> headers = Map.of(
                "X-Forwarded-For", "192.168.88.1",
                "X-User-Id", "ent-user-1",
                "X-User-Tier", "PREMIUM"
        );

        TestExecutionResult result = executeSequentialRequests(totalRequests, headers);

        // Non-batched IP Token Bucket limit is 8 -> expect 8 allowed, 4 rejected
        result.assertZeroErrors();
    }

    @Test
    @DisplayName("PREMIUM tier local batching supports sustained high-volume throughput")
    void testEnterpriseLocalBatchingHighVolume() throws Exception {
        int totalRequests = 50;

        // Distribute requests across different IPs so IP-level limits (8) are not breached
        TestExecutionResult result = executeSequentialRequestsWithDynamicHeaders(totalRequests, index -> Map.of(
                "X-Forwarded-For", "10.0.1." + index,
                "X-User-Id", "ent-high-volume-user",
                "X-User-Tier", "PREMIUM"
        ));

        // Enterprise limit is 500/s with batching -> all 50 requests should be allowed
        result.assertExactCounts(50, 0);
    }
}