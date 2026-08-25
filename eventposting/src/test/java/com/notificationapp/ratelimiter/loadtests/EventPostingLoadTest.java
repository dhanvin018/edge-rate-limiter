package com.notificationapp.ratelimiter.loadtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "authentication.required=false")
class EventPostingLoadTest {
    private static final Logger logger = LoggerFactory.getLogger(EventPostingLoadTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Load Test: Send 499 concurrent requests to /api/events/postEvent")
    void testSend499RequestsMockMvc() throws InterruptedException, IOException {
        int totalRequests = 700;
        int threadPoolSize = 50;

        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch startLatch = new CountDownLatch(1); // Synchronizes simultaneous release
        CountDownLatch doneLatch = new CountDownLatch(totalRequests); // Waits for all requests to finish

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        ClassPathResource resource =
                new ClassPathResource("payloads/sample-payload.json");

        String samplePayload = new String(
                resource.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Hold execution until all threads are queued

                    MvcResult result = mockMvc.perform(post("/api/events/postEvent")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(samplePayload))
                            .andReturn();
                    int statusCode = result.getResponse().getStatus();

                    if (statusCode >= 200 && statusCode < 300) {
                        successCount.incrementAndGet();
                    } else if (statusCode == 429) {
                        rateLimitedCount.incrementAndGet();
                    } else {
                        otherErrorCount.incrementAndGet();
                        System.err.printf("[HTTP %d Error]: %s%n",
                                statusCode,
                                result.getResponse().getContentAsString());
                    }
                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();// Log thread execution exceptions (e.g., Redis connection timeouts)
                    System.err.println("[Thread Exception]: " + e.getClass().getName() + " - " + e.getMessage());
//                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();

        // Wait for all 499 requests to complete
        doneLatch.await();
        executor.shutdown();

        // Diagnostic output
        logger.info("================ LOAD TEST RESULTS ================");
        logger.info("Total Requests Executed: {}", totalRequests);
        logger.info("Successful (2xx): {}", successCount.get());
        logger.info("Rate Limited (429): {}", rateLimitedCount.get());
        logger.info("Other Failures: {}", otherErrorCount.get());
        logger.info("===================================================");

        // Verification
        assertThat(successCount.get() + rateLimitedCount.get() + otherErrorCount.get())
                .isEqualTo(totalRequests);
        assertThat(otherErrorCount.get()).isEqualTo(0);
    }
}
