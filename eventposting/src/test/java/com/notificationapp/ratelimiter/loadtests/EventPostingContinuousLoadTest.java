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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@TestPropertySource(properties = "authentication.required=false")
class EventPostingContinuousLoadTest {

    private static final Logger logger =
            LoggerFactory.getLogger(EventPostingContinuousLoadTest.class);

    /*
     * ============================================================
     * TEST CONFIGURATION
     * ============================================================
     */

    private static final String BASE_URL =
            "http://localhost:8080";

    private static final String ENDPOINT =
            "/api/events/postEvent";

    /**
     * Total duration of the traffic simulation.
     */
    private static final int TEST_DURATION_SECONDS = 90;

    /**
     * Number of client threads generating requests.
     */
    private static final int THREAD_POOL_SIZE = 1;

    /**
     * Normal traffic rate.
     * <p>
     * Example:
     * 5 requests/sec during normal traffic.
     */
    private static final int NORMAL_REQUESTS_PER_SECOND = 5;

    /**
     * Generate a burst every N seconds.
     */
    private static final int BURST_INTERVAL_SECONDS = 10;

    /**
     * Number of requests generated during a burst.
     */
    private static final int BURST_REQUEST_COUNT = 100;

    /**
     * HTTP client connection timeout.
     */
    private static final int HTTP_CONNECT_TIMEOUT_SECONDS = 10;

    /**
     * Request timeout.
     */
    private static final int HTTP_REQUEST_TIMEOUT_SECONDS = 30;


    /*
     * ============================================================
     * TEST
     * ============================================================
     */

    @Test
    @DisplayName(
            "90 Second Continuous Traffic + Burst Rate Limiter Test"
    )
    void testContinuousTrafficAndBursts()
            throws Exception {

        /*
         * --------------------------------------------------------
         * Load payload
         * --------------------------------------------------------
         */

        ClassPathResource resource =
                new ClassPathResource(
                        "payloads/sample-payload.json"
                );

        String samplePayload =
                new String(
                        resource.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8
                );


        /*
         * --------------------------------------------------------
         * HTTP client
         * --------------------------------------------------------
         */

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofSeconds(
                                        HTTP_CONNECT_TIMEOUT_SECONDS
                                )
                        )
                        .build();


        /*
         * --------------------------------------------------------
         * Thread pool
         * --------------------------------------------------------
         */

        ExecutorService executor =
                Executors.newFixedThreadPool(
                        THREAD_POOL_SIZE
                );


        /*
         * --------------------------------------------------------
         * Result storage
         * --------------------------------------------------------
         */

        List<RequestResult> results =
                Collections.synchronizedList(
                        new ArrayList<>()
                );


        /*
         * --------------------------------------------------------
         * Counters
         * --------------------------------------------------------
         */

        AtomicInteger successCount =
                new AtomicInteger();

        AtomicInteger rateLimitedCount =
                new AtomicInteger();

        AtomicInteger otherErrorCount =
                new AtomicInteger();


        /*
         * --------------------------------------------------------
         * Test start
         * --------------------------------------------------------
         */

        Instant testStartTime =
                Instant.now();

        long testStartNanos =
                System.nanoTime();

        logger.info("");
        logger.info(
                "=========================================================="
        );
        logger.info(
                "STARTING CONTINUOUS RATE LIMITER LOAD TEST"
        );
        logger.info(
                "=========================================================="
        );
        logger.info(
                "Start Time             : {}",
                testStartTime
        );
        logger.info(
                "Duration               : {} seconds",
                TEST_DURATION_SECONDS
        );
        logger.info(
                "Thread Pool Size       : {}",
                THREAD_POOL_SIZE
        );
        logger.info(
                "Normal Traffic         : {} requests/sec",
                NORMAL_REQUESTS_PER_SECOND
        );
        logger.info(
                "Burst Interval         : {} seconds",
                BURST_INTERVAL_SECONDS
        );
        logger.info(
                "Burst Request Count    : {}",
                BURST_REQUEST_COUNT
        );
        logger.info(
                "=========================================================="
        );


        /*
         * --------------------------------------------------------
         * Main traffic loop
         * --------------------------------------------------------
         */

        for (int second = 0;
             second < TEST_DURATION_SECONDS;
             second++) {

            long secondStartNanos =
                    System.nanoTime();


            /*
             * ====================================================
             * BURST
             * ====================================================
             *
             * Every N seconds generate a large number of
             * requests concurrently.
             */

            if (second > 0 &&
                    second % BURST_INTERVAL_SECONDS == 0) {

                executeBurst(
                        second,
                        httpClient,
                        executor,
                        samplePayload,
                        results,
                        successCount,
                        rateLimitedCount,
                        otherErrorCount
                );
            }


            /*
             * ====================================================
             * NORMAL TRAFFIC
             * ====================================================
             */

            CountDownLatch normalLatch =
                    new CountDownLatch(
                            NORMAL_REQUESTS_PER_SECOND
                    );

            for (int i = 0;
                 i < NORMAL_REQUESTS_PER_SECOND;
                 i++) {

                submitRequest(
                        httpClient,
                        executor,
                        samplePayload,
                        results,
                        successCount,
                        rateLimitedCount,
                        otherErrorCount,
                        normalLatch
                );
            }

            /*
             * Wait until normal requests finish.
             *
             * This keeps the traffic approximately
             * synchronized with the one-second intervals.
             */

            normalLatch.await();


            /*
             * ----------------------------------------------------
             * Maintain one-second traffic interval
             * ----------------------------------------------------
             */

            long elapsedMillis =
                    TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime()
                                    - secondStartNanos
                    );

            long remainingMillis =
                    1000 - elapsedMillis;

            if (remainingMillis > 0) {
                Thread.sleep(remainingMillis);
            }
        }


        /*
         * --------------------------------------------------------
         * Shutdown
         * --------------------------------------------------------
         */

        executor.shutdown();

        if (!executor.awaitTermination(
                30,
                TimeUnit.SECONDS)) {

            logger.warn(
                    "Executor did not terminate within timeout."
            );

            executor.shutdownNow();
        }


        /*
         * --------------------------------------------------------
         * Test completion
         * --------------------------------------------------------
         */

        Instant testEndTime =
                Instant.now();

        long totalDurationMillis =
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime()
                                - testStartNanos
                );


        /*
         * --------------------------------------------------------
         * Calculate results
         * --------------------------------------------------------
         */

        printResults(
                results,
                successCount.get(),
                rateLimitedCount.get(),
                otherErrorCount.get(),
                testStartTime,
                testEndTime,
                totalDurationMillis
        );
    }


    /*
     * ============================================================
     * BURST EXECUTION
     * ============================================================
     */

    private void executeBurst(
            int second,
            HttpClient httpClient,
            ExecutorService executor,
            String samplePayload,
            List<RequestResult> results,
            AtomicInteger successCount,
            AtomicInteger rateLimitedCount,
            AtomicInteger otherErrorCount
    ) throws InterruptedException {

        logger.info("");
        logger.info(
                ">>>>>>>>>> BURST START: second={} <<<<<<<<<<",
                second
        );

        Instant burstStartTime =
                Instant.now();

        long burstStartNanos =
                System.nanoTime();

        CountDownLatch burstLatch =
                new CountDownLatch(
                        BURST_REQUEST_COUNT
                );


        /*
         * Submit all burst requests without waiting
         * between requests.
         */

        for (int i = 0;
             i < BURST_REQUEST_COUNT;
             i++) {

            submitRequest(
                    httpClient,
                    executor,
                    samplePayload,
                    results,
                    successCount,
                    rateLimitedCount,
                    otherErrorCount,
                    burstLatch
            );
        }


        /*
         * Wait until all burst requests complete.
         */

        burstLatch.await();


        long burstDurationMillis =
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime()
                                - burstStartNanos
                );

        Instant burstEndTime =
                Instant.now();


        logger.info(
                "Burst Start Time : {}",
                burstStartTime
        );

        logger.info(
                "Burst End Time   : {}",
                burstEndTime
        );

        logger.info(
                "Burst Duration   : {} ms",
                burstDurationMillis
        );

        logger.info(
                "Burst Requests   : {}",
                BURST_REQUEST_COUNT
        );

        logger.info(
                ">>>>>>>>>> BURST END: second={} <<<<<<<<<<",
                second
        );
    }


    /*
     * ============================================================
     * REQUEST EXECUTION
     * ============================================================
     */

    private void submitRequest(
            HttpClient httpClient,
            ExecutorService executor,
            String samplePayload,
            List<RequestResult> results,
            AtomicInteger successCount,
            AtomicInteger rateLimitedCount,
            AtomicInteger otherErrorCount,
            CountDownLatch latch
    ) {

        executor.submit(() -> {

            Instant requestSentTime =
                    Instant.now();

            long requestStartNanos =
                    System.nanoTime();

            try {

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                BASE_URL
                                                        + ENDPOINT
                                        )
                                )
                                .timeout(
                                        Duration.ofSeconds(
                                                HTTP_REQUEST_TIMEOUT_SECONDS
                                        )
                                )
                                .header(
                                        "Content-Type",
                                        "application/json"
                                )
                                .POST(
                                        HttpRequest
                                                .BodyPublishers
                                                .ofString(
                                                        samplePayload
                                                )
                                )
                                .build();


                /*
                 * ------------------------------------------------
                 * Send actual HTTP request
                 * ------------------------------------------------
                 */

                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers
                                        .ofString()
                        );


                /*
                 * ------------------------------------------------
                 * Response timestamp
                 * ------------------------------------------------
                 */

                Instant responseReceivedTime =
                        Instant.now();

                long latencyNanos =
                        System.nanoTime()
                                - requestStartNanos;

                int statusCode =
                        response.statusCode();


                /*
                 * ------------------------------------------------
                 * Store result
                 * ------------------------------------------------
                 */

                results.add(
                        new RequestResult(
                                requestSentTime,
                                responseReceivedTime,
                                latencyNanos,
                                statusCode
                        )
                );


                /*
                 * ------------------------------------------------
                 * Categorize response
                 * ------------------------------------------------
                 */

                if (statusCode >= 200 &&
                        statusCode < 300) {

                    successCount.incrementAndGet();

                } else if (statusCode == 429) {

                    rateLimitedCount.incrementAndGet();

                } else {

                    otherErrorCount.incrementAndGet();

                    logger.error(
                            "[HTTP {} Error]: {}",
                            statusCode,
                            response.body()
                    );
                }

            } catch (Exception e) {

                otherErrorCount.incrementAndGet();

                logger.error(
                        "[Request Exception]: {} - {}",
                        e.getClass().getName(),
                        e.getMessage(),
                        e
                );

            } finally {

                latch.countDown();
            }
        });
    }


    /*
     * ============================================================
     * FINAL RESULTS
     * ============================================================
     */

    private void printResults(
            List<RequestResult> results,
            int successCount,
            int rateLimitedCount,
            int otherErrorCount,
            Instant testStartTime,
            Instant testEndTime,
            long totalDurationMillis
    ) {

        if (results.isEmpty()) {

            logger.warn(
                    "No HTTP responses were recorded."
            );

            return;
        }


        /*
         * --------------------------------------------------------
         * Extract latencies
         * --------------------------------------------------------
         */

        List<Long> latencies =
                results.stream()
                        .map(
                                RequestResult
                                        ::getLatencyMillis
                        )
                        .sorted()
                        .toList();


        /*
         * --------------------------------------------------------
         * Latency statistics
         * --------------------------------------------------------
         */

        long minLatency =
                latencies.get(0);

        long maxLatency =
                latencies.get(
                        latencies.size() - 1
                );

        double averageLatency =
                latencies.stream()
                        .mapToLong(Long::longValue)
                        .average()
                        .orElse(0);

        long p50 =
                percentile(
                        latencies,
                        50
                );

        long p95 =
                percentile(
                        latencies,
                        95
                );

        long p99 =
                percentile(
                        latencies,
                        99
                );


        /*
         * --------------------------------------------------------
         * Throughput
         * --------------------------------------------------------
         */

        double requestsPerSecond =
                results.size()
                        / (totalDurationMillis / 1000.0);


        /*
         * --------------------------------------------------------
         * Request timestamps
         * --------------------------------------------------------
         */

        Instant firstRequest =
                results.stream()
                        .map(
                                RequestResult
                                        ::getRequestSentTime
                        )
                        .min(Instant::compareTo)
                        .orElse(null);

        Instant lastRequest =
                results.stream()
                        .map(
                                RequestResult
                                        ::getRequestSentTime
                        )
                        .max(Instant::compareTo)
                        .orElse(null);

        Instant firstResponse =
                results.stream()
                        .map(
                                RequestResult
                                        ::getResponseReceivedTime
                        )
                        .min(Instant::compareTo)
                        .orElse(null);

        Instant lastResponse =
                results.stream()
                        .map(
                                RequestResult
                                        ::getResponseReceivedTime
                        )
                        .max(Instant::compareTo)
                        .orElse(null);


        /*
         * --------------------------------------------------------
         * Print results
         * --------------------------------------------------------
         */

        logger.info("");
        logger.info(
                "=========================================================="
        );
        logger.info(
                "              RATE LIMITER TEST RESULTS"
        );
        logger.info(
                "=========================================================="
        );

        logger.info(
                "Test Start Time       : {}",
                testStartTime
        );

        logger.info(
                "Test End Time         : {}",
                testEndTime
        );

        logger.info(
                "Duration              : {} ms",
                totalDurationMillis
        );

        logger.info(
                "First Request Sent    : {}",
                firstRequest
        );

        logger.info(
                "Last Request Sent     : {}",
                lastRequest
        );

        logger.info(
                "First Response        : {}",
                firstResponse
        );

        logger.info(
                "Last Response         : {}",
                lastResponse
        );

        logger.info(
                "----------------------------------------------------------"
        );

        logger.info(
                "Total Requests        : {}",
                results.size()
        );

        logger.info(
                "Successful (2xx)      : {}",
                successCount
        );

        logger.info(
                "Rate Limited (429)    : {}",
                rateLimitedCount
        );

        logger.info(
                "Other Failures        : {}",
                otherErrorCount
        );

        logger.info(
                "Requests / Second     : {}",
                String.format(
                        "%.2f",
                        requestsPerSecond
                )
        );

        logger.info(
                "----------------------------------------------------------"
        );

        logger.info(
                "Min Latency           : {} ms",
                minLatency
        );

        logger.info(
                "Average Latency       : {} ms",
                String.format(
                        "%.2f",
                        averageLatency
                )
        );

        logger.info(
                "P50 Latency           : {} ms",
                p50
        );

        logger.info(
                "P95 Latency           : {} ms",
                p95
        );

        logger.info(
                "P99 Latency           : {} ms",
                p99
        );

        logger.info(
                "Max Latency           : {} ms",
                maxLatency
        );

        logger.info(
                "=========================================================="
        );
    }


    /*
     * ============================================================
     * PERCENTILE
     * ============================================================
     */

    private long percentile(
            List<Long> sortedValues,
            double percentile
    ) {

        int index =
                (int) Math.ceil(
                        percentile
                                / 100.0
                                * sortedValues.size()
                ) - 1;

        index =
                Math.max(
                        0,
                        Math.min(
                                index,
                                sortedValues.size() - 1
                        )
                );

        return sortedValues.get(index);
    }


    /*
     * ============================================================
     * REQUEST RESULT
     * ============================================================
     */

    private static class RequestResult {

        private final Instant requestSentTime;

        private final Instant responseReceivedTime;

        private final long latencyNanos;

        private final int statusCode;


        private RequestResult(
                Instant requestSentTime,
                Instant responseReceivedTime,
                long latencyNanos,
                int statusCode
        ) {

            this.requestSentTime =
                    requestSentTime;

            this.responseReceivedTime =
                    responseReceivedTime;

            this.latencyNanos =
                    latencyNanos;

            this.statusCode =
                    statusCode;
        }


        private long getLatencyMillis() {

            return TimeUnit.NANOSECONDS.toMillis(
                    latencyNanos
            );
        }


        private Instant getRequestSentTime() {

            return requestSentTime;
        }


        private Instant getResponseReceivedTime() {

            return responseReceivedTime;
        }


        private int getStatusCode() {

            return statusCode;
        }
    }
}