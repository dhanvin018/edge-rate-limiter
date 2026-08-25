package com.notificationapp.ratelimiter.loadtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "authentication.required=false")
class EventPostingLoadTestHttp {
    private static final Logger logger = LoggerFactory.getLogger(EventPostingLoadTestHttp.class);

    @Test
    @DisplayName("Load Test: Send 1300 concurrent HTTP requests to /api/events/postEvent")
    void testSend1300RequestsHttp() throws InterruptedException, IOException {
        int totalRequests = 1300;
        int threadPoolSize = 1;

        String baseUrl = "http://localhost:8080";

        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        // Thread-safe collection to store latency of each request in milliseconds
        List<Long> latenciesMs = new CopyOnWriteArrayList<>();

        ClassPathResource resource =
                new ClassPathResource("payloads/sample-payload.json");

        String samplePayload = new String(
                resource.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        AtomicReference<Instant> firstRequestTime = new AtomicReference<>();
        AtomicReference<Instant> lastRequestTime = new AtomicReference<>();
        AtomicReference<Instant> firstResponseTime = new AtomicReference<>();
        AtomicReference<Instant> lastResponseTime = new AtomicReference<>();

        for (int i = 0; i < totalRequests; i++) {
            final int requestId = i + 1;

            executor.submit(() -> {
                try {
                    startLatch.await();

                    Instant requestStartTime = Instant.now();
                    firstRequestTime.accumulateAndGet(requestStartTime,
                            (curr, val) -> curr == null || val.isBefore(curr) ? val : curr);
                    lastRequestTime.accumulateAndGet(requestStartTime,
                            (curr, val) -> curr == null || val.isAfter(curr) ? val : curr);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/events/postEvent"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(samplePayload))
                            .build();

                    // High-resolution clock start
                    long startNano = System.nanoTime();

                    HttpResponse<String> response = httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

                    // High-resolution clock end & duration calculation
                    long elapsedNano = System.nanoTime() - startNano;
                    long latencyMs = elapsedNano / 1_000_000;
                    latenciesMs.add(latencyMs);

                    Instant responseEndTime = Instant.now();
                    firstResponseTime.accumulateAndGet(responseEndTime,
                            (curr, val) -> curr == null || val.isBefore(curr) ? val : curr);
                    lastResponseTime.accumulateAndGet(responseEndTime,
                            (curr, val) -> curr == null || val.isAfter(curr) ? val : curr);

                    int statusCode = response.statusCode();

                    if (statusCode >= 200 && statusCode < 300) {
                        successCount.incrementAndGet();
                        logger.debug("REQ #{}: Status={} | Latency={} ms", requestId, statusCode, latencyMs);
                    } else if (statusCode == 429) {
                        rateLimitedCount.incrementAndGet();
                        logger.debug("REQ #{}: Status={} (Rate Limited) | Latency={} ms", requestId, statusCode, latencyMs);
                    } else {
                        otherErrorCount.incrementAndGet();
                        logger.error("[HTTP {} Error] REQ #{}: {} | Latency={} ms", statusCode, requestId, response.body(), latencyMs);
                    }

                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();
                    logger.error("[Thread Exception] REQ #{}: {} - {}", requestId, e.getClass().getName(), e.getMessage(), e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release waiting threads simultaneously
        startLatch.countDown();

        // Wait for all requests to finish
        doneLatch.await();
        executor.shutdown();

        // Calculate latency metrics
        double averageLatencyMs = latenciesMs.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);

        long maxLatencyMs = latenciesMs.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);

        long minLatencyMs = latenciesMs.stream()
                .mapToLong(Long::longValue)
                .min()
                .orElse(0L);

        // Diagnostic logs
        logger.info("================ LOAD TEST RESULTS ================");
        logger.info("Total Requests Executed: {}", totalRequests);
        logger.info("Successful (2xx): {}", successCount.get());
        logger.info("Rate Limited (429): {}", rateLimitedCount.get());
        logger.info("Other Failures: {}", otherErrorCount.get());
        logger.info("---------------------------------------------------");
        logger.info("Average Latency        : {} ms", String.format("%.2f", averageLatencyMs));
        logger.info("Min Latency            : {} ms", minLatencyMs);
        logger.info("Max Latency            : {} ms", maxLatencyMs);
        logger.info("---------------------------------------------------");
        logger.info("First Request Started  : {}", firstRequestTime.get());
        logger.info("Last Request Started   : {}", lastRequestTime.get());
        logger.info("First Response Received: {}", firstResponseTime.get());
        logger.info("Last Response Received : {}", lastResponseTime.get());
        logger.info("===================================================");

        // Assertions
        assertThat(successCount.get() + rateLimitedCount.get() + otherErrorCount.get())
                .isEqualTo(totalRequests);

        assertThat(otherErrorCount.get())
                .isEqualTo(0);
    }
}