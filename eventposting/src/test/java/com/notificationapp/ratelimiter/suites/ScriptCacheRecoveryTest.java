package com.notificationapp.ratelimiter.suites;

import com.notificationapp.ratelimiter.base.BaseRateLimiterTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "authentication.required=false",
        "spring.config.import=optional:classpath:Config/all-strategies-global.yml",
        "test.payload.path=payloads/sample-payload.json"
})
class ScriptCacheRecoveryTest extends BaseRateLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("Commits for all four strategies recover after Redis loses its script cache")
    void commitsRecoverAfterScriptCacheLoss() throws Exception {
        // A Redis restart empties the script cache in the same way
        try (RedisConnection connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
            connection.scriptingCommands().scriptFlush();
        }

        TestExecutionResult result = executeSequentialRequests(7, Map.of());

        result.assertExactCounts(5, 2);
        for (String strategy : List.of("FIXED_WINDOW", "SLIDING_WINDOW_LOG", "SLIDING_WINDOW_COUNTER", "TOKEN_BUCKET")) {
            assertThat(redisTemplate.keys("rl:global:\\*:" + strategy + ":*"))
                    .as("state committed for %s", strategy)
                    .isNotEmpty();
        }
    }
}
