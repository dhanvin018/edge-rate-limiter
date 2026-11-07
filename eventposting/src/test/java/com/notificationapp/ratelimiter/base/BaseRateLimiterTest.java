package com.notificationapp.ratelimiter.base;

import com.notificationapp.eventposting.service.EventPublisherService;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "event.posting.enabled=true"
)
public abstract class BaseRateLimiterTest {

    protected static final Logger log = LoggerFactory.getLogger(BaseRateLimiterTest.class);
    protected static final String BASE_URL = "http://localhost:";
    protected static final String API_ENDPOINT = "/api/events/postEvent";
    @LocalServerPort
    protected int port;

    // These suites test the limiter, so requests stop at the controller instead of needing Kafka
    @MockitoBean
    private EventPublisherService eventPublisherService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Inject payload path from @TestPropertySource with a default fallback
    @Value("${test.payload.path:payloads/sample-payload.json}")
    private String payloadPath;

    protected HttpClient httpClient;
    private String samplePayload;

    @BeforeEach
    void setUpStateIsolationAndPayload() throws IOException {
        // 1. Flush Redis DB before every test run
        try {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
            log.info(">>> Redis DB flushed successfully.");
        } catch (Exception e) {
            log.error("Failed to flush Redis state", e);
        }

        // 2. Read payload from classpath using injected property
        this.samplePayload = loadPayloadFromResources(payloadPath);

        // 3. Initialize high-concurrency Virtual Thread HttpClient
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private String loadPayloadFromResources(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Concurrent execution harness remains identical...
     */
    protected TestExecutionResult executeConcurrentRequests(
            int totalRequests,
            Map<String, String> customHeaders) throws InterruptedException {

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalRequests; i++) {
                final int requestId = i + 1;

                executor.submit(() -> {
                    try {
                        startLatch.await(); // Synchronizes burst start time

                        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                                .uri(URI.create(BASE_URL + port+ API_ENDPOINT))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(samplePayload));

                        if (customHeaders != null) {
                            customHeaders.forEach(requestBuilder::header);
                        }

                        HttpResponse<String> response = httpClient.send(
                                requestBuilder.build(),
                                HttpResponse.BodyHandlers.ofString()
                        );

                        int status = response.statusCode();
                        if (status >= 200 && status < 300) {
                            successCount.incrementAndGet();
                        } else if (status == 429) {
                            rateLimitedCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                            log.error("[Unexpected HTTP {}] REQ #{}: {}", status, requestId, response.body());
                        }

                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        log.error("Execution exception on REQ #{}", requestId, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Fire all requests simultaneously
            doneLatch.await();      // Wait for completion
        }

        executionSummary(totalRequests, successCount, rateLimitedCount, errorCount);

        return new TestExecutionResult(
                successCount.get(),
                rateLimitedCount.get(),
                errorCount.get()
        );
    }

    /**
     * Executes requests sequentially one after another on a single thread.
     * Ideal for deterministic step-by-step state testing and precise Redis inspection.
     */
    protected TestExecutionResult executeSequentialRequests(
            int totalRequests,
            Map<String, String> customHeaders) {

        int successCount = 0;
        int rateLimitedCount = 0;
        int errorCount = 0;

        log.info("Starting sequential execution of {} requests...", totalRequests);

        for (int i = 1; i <= totalRequests; i++) {
            if(i==9){
                log.info("Request #{} is queued for execution.", i);
            }
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + port+ API_ENDPOINT))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(samplePayload));

                if (customHeaders != null) {
                    customHeaders.forEach(requestBuilder::header);
                }

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    successCount++;
                } else if (status == 429) {
                    rateLimitedCount++;
                } else {
                    errorCount++;
                    log.error("[Unexpected HTTP {}] REQ #{}: {}", status, i, response.body());
                }

                log.debug("REQ #{}/{} -> Status: {}", i, totalRequests, status);

            } catch (Exception e) {
                errorCount++;
                log.error("Execution exception on REQ #{}", i, e);
            }
        }

        executionSummary(totalRequests, new AtomicInteger(successCount), new AtomicInteger(rateLimitedCount), new AtomicInteger(errorCount));

        return new TestExecutionResult(
                successCount,
                rateLimitedCount,
                errorCount
        );
    }

    protected TestExecutionResult executeSequentialRequestsWithDynamicHeaders(
            int totalRequests,
            java.util.function.IntFunction<Map<String, String>> dynamicHeadersSupplier) {

        int successCount = 0;
        int rateLimitedCount = 0;
        int errorCount = 0;

        log.info("Starting sequential execution of {} requests with dynamic headers...", totalRequests);

        for (int i = 1; i <= totalRequests; i++) {
            if (i == 9) {
                log.info("Request #{} is queued for execution.", i);
            }
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + port + API_ENDPOINT))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(samplePayload));

                // Resolve dynamic headers for request index 'i' (or i - 1 if zero-based logic is needed)
                if (dynamicHeadersSupplier != null) {
                    Map<String, String> currentHeaders = dynamicHeadersSupplier.apply(i);
                    if (currentHeaders != null) {
                        currentHeaders.forEach(requestBuilder::header);
                    }
                }

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    successCount++;
                } else if (status == 429) {
                    rateLimitedCount++;
                } else {
                    errorCount++;
                    log.error("[Unexpected HTTP {}] REQ #{}: {}", status, i, response.body());
                }

                log.debug("REQ #{}/{} -> Status: {}", i, totalRequests, status);

            } catch (Exception e) {
                errorCount++;
                log.error("Execution exception on REQ #{}", i, e);
            }
        }

        executionSummary(totalRequests, new AtomicInteger(successCount), new AtomicInteger(rateLimitedCount), new AtomicInteger(errorCount));

        return new TestExecutionResult(
                successCount,
                rateLimitedCount,
                errorCount
        );
    }



    private static void executionSummary(int totalRequests, AtomicInteger successCount, AtomicInteger rateLimitedCount, AtomicInteger errorCount) {
        // Diagnostic Summary
        log.info("================ EXECUTION SUMMARY ================");
        log.info("Total Requests Executed : {}", totalRequests);
        log.info("Successful (2xx)        : {}", successCount.get());
        log.info("Rate Limited (429)      : {}", rateLimitedCount.get());
        log.info("System Errors (5xx/Ex)  : {}", errorCount.get());
        log.info("===================================================");
    }

    public record TestExecutionResult(
            int successCount,
            int rateLimitedCount,
            int errorCount
    ) {
        public void assertZeroErrors() {
            assertThat(errorCount).as("Infrastructure errors during test execution").isEqualTo(0);
        }

        public void assertExactCounts(int expectedSuccess, int expectedRateLimited) {
            assertZeroErrors();
            assertThat(successCount)
                    .as("Successful requests (2xx)")
                    .isEqualTo(expectedSuccess);
            assertThat(rateLimitedCount)
                    .as("Rate limited requests (429)")
                    .isEqualTo(expectedRateLimited);
        }
    }
}