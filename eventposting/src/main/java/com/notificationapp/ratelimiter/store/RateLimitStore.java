package com.notificationapp.ratelimiter.store;

import com.notificationapp.ratelimiter.model.RedisCommitModel;
import com.notificationapp.ratelimiter.model.WindowRule;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface RateLimitStore {
    <T> Optional<T> get(String key, Class<T> type);
    void put(String key, Object value, Duration ttl);
    long incrementAndGet(String key, long delta, Duration ttl);
    boolean compareAndSwap(String key, Object expected, Object updated, Duration ttl);
    boolean executePipeline(List<RedisCommitModel> commitItems);

    // --- Fixed Window Operations ---
    boolean evaluateFixedWindowRead(String windowKey, long capacity, int cost);
    boolean evaluateFixedWindowAtomic(String windowKey, long capacity, long windowSizeMs, int cost);
    void commitFixedWindowToPipeline(String windowKey, int cost, long windowSizeMs);

    // --- Sliding Window Log Operations ---
    boolean evaluateSlidingWindowLogRead(String key, long capacity, long windowMs, long now);
    boolean evaluateSlidingWindowLogAtomic(String key, long capacity, long windowMs, long now, String uniqueMemberId);
    void commitSlidingWindowLogToPipeline(String key, long windowMs, long now, String uniqueMemberId);

    // --- Sliding Window Counter Operations ---
    boolean evaluateSlidingWindowCounterRead(String currentKey, String previousKey, long capacity, long windowMs, long now, int cost);
    boolean evaluateSlidingWindowCounterAtomic(String currentKey, String previousKey, long capacity, long windowMs, long now, int cost);
    void commitSlidingWindowCounterToPipeline(String currentKey, int cost, long windowMs);

    // --- Token Bucket Operations ---
    boolean inspectTokenBucketRead(String key, long capacity, double refillRatePerMs, int cost);
    long leaseTokenBucket(String key, long capacity, double refillRatePerMs, int requestedBatch);
    void commitTokenBucketToPipeline(String key, long capacity, double refillRatePerMs, int cost, long now);

    // --- Utility Methods ---
    Duration getTokenBucketRetryAfter(String key, WindowRule rule);
}
