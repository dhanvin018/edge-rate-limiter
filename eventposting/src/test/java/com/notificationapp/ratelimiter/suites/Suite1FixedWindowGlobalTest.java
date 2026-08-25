package com.notificationapp.ratelimiter.suites;

import com.notificationapp.ratelimiter.base.BaseRateLimiterTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "authentication.required=false",
        "spring.config.import=optional:classpath:Config/fixed-window-global.yml",
        "test.payload.path=payloads/sample-payload.json"
})
class Suite1FixedWindowGlobalTest extends BaseRateLimiterTest {

    @Test
    @DisplayName("TC-01: Fixed Window Global - Capacity Enforcement")
    void testFixedWindowGlobalCapacity() throws Exception {
        int totalRequests = 40;

        TestExecutionResult result = executeSequentialRequests(totalRequests, Map.of());


        result.assertExactCounts(30, totalRequests - 30);
    }
}