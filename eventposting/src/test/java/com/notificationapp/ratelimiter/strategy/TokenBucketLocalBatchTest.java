package com.notificationapp.ratelimiter.strategy;

import com.notificationapp.ratelimiter.model.Dimension;
import com.notificationapp.ratelimiter.model.LimitStrategyType;
import com.notificationapp.ratelimiter.model.LocalBatchConfig;
import com.notificationapp.ratelimiter.model.WindowRule;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenBucketLocalBatchTest {

    private static final String KEY = "rl:global:*:TOKEN_BUCKET:PT1S";
    private static final WindowRule RULE = new WindowRule(
            Dimension.GLOBAL, Duration.ofSeconds(1), 100, LimitStrategyType.TOKEN_BUCKET, null,
            new LocalBatchConfig(true, 5, 0));

    private final TokenBucketStrategy strategy = new TokenBucketStrategy(Clock.systemUTC());
    private final RateLimitStore store = mock(RateLimitStore.class);

    @Test
    @DisplayName("Concurrent checks never admit more requests than tokens leased")
    void concurrentChecksDoNotOverSpend() throws Exception {
        when(store.leaseTokenBucket(eq(KEY), anyLong(), anyDouble(), anyInt())).thenReturn(5L, 0L);
        int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                if (strategy.canConsume(KEY, RULE, 1, store)) {
                    admitted.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(admitted.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("Released tokens are reused without leasing again")
    void releasedTokensAreReused() {
        when(store.leaseTokenBucket(eq(KEY), anyLong(), anyDouble(), anyInt())).thenReturn(1L, 0L);

        assertThat(strategy.canConsume(KEY, RULE, 1, store)).isTrue();
        strategy.release(KEY, RULE, 1, store);

        assertThat(strategy.canConsume(KEY, RULE, 1, store)).isTrue();
        verify(store, times(1)).leaseTokenBucket(eq(KEY), anyLong(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("Partial lease grants are kept rather than lost")
    void partialGrantsAreKept() {
        when(store.leaseTokenBucket(eq(KEY), anyLong(), anyDouble(), anyInt())).thenReturn(2L, 1L);

        assertThat(strategy.canConsume(KEY, RULE, 3, store)).isFalse();
        assertThat(strategy.canConsume(KEY, RULE, 3, store)).isTrue();
    }

    @Test
    @DisplayName("Commit does not consume batched tokens a second time")
    void commitDoesNotDoubleCharge() {
        when(store.leaseTokenBucket(eq(KEY), anyLong(), anyDouble(), anyInt())).thenReturn(2L, 0L);

        assertThat(strategy.canConsume(KEY, RULE, 1, store)).isTrue();
        strategy.commit(KEY, RULE, 1, store);

        assertThat(strategy.canConsume(KEY, RULE, 1, store)).isTrue();
    }
}
