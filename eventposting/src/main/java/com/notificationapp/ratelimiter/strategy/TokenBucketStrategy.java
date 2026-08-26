package com.notificationapp.ratelimiter.strategy;

import com.notificationapp.ratelimiter.model.LimitStrategyType;
import com.notificationapp.ratelimiter.model.LocalBatchConfig;
import com.notificationapp.ratelimiter.model.WindowRule;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenBucketStrategy implements LimitStrategy {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketStrategy.class);

    private final Clock clock;

    // Concurrent map keeping active local buckets in memory
    private final ConcurrentHashMap<String, LocalTokenBucket> localBuckets = new ConcurrentHashMap<>();

    public TokenBucketStrategy(Clock clock) {
        this.clock = clock;
    }

    @Override
    public LimitStrategyType type() {
        return LimitStrategyType.TOKEN_BUCKET;
    }


    @Override
    public boolean canConsume(String key, WindowRule rule, int cost, RateLimitStore store) {
        double refillRatePerMs = (double) rule.limit() / rule.windowSize().toMillis();
        long capacity = rule.effectiveCapacity();
        LocalBatchConfig localBatch = rule.localBatch();

        // CASE 1: Local Batching Disabled -> Direct Redis lease check via Store
        if (localBatch == null || !localBatch.enabled()) {
            return store.inspectTokenBucketRead(key, capacity, refillRatePerMs, cost);
        }

        // CASE 2: Local Batching Enabled
        LocalTokenBucket localBucket = localBuckets.computeIfAbsent(
                key,
                k -> new LocalTokenBucket(capacity, refillRatePerMs)
        );

        // 1. Check local inventory cache first
        if (localBucket.hasSufficientTokens(cost)) {
            log.trace("Phase 1: Local inventory sufficient ({}) for cost={} on key={}",
                    localBucket.availableTokens(), cost, key);
            return true;
        }

        // 2. Local inventory depleted -> Lease a full token batch from Redis once via Store
        int configuredBatchSize = localBatch.batchSize();
        int batchToRequest = (int) Math.min(capacity, Math.max(cost, configuredBatchSize));

        log.debug("Local bucket depleted for key={}. Requesting batch size={} from Redis", key, batchToRequest);

        long granted = store.leaseTokenBucket(key, capacity, refillRatePerMs, batchToRequest);

        if (granted >= cost) {
            // Immediately stock local memory inventory with the newly leased batch
            localBucket.depositLeasedTokens(granted);
            log.debug("Phase 1: Successfully stocked local inventory with {} tokens for key={}", granted, key);
            return true;
        }

        log.debug("Phase 1: Redis rate limit exceeded for key={}. Granted {} token(s), required cost={}", key, granted, cost);
        return false;
    }

    @Override
    public void commit(String key, WindowRule rule, int cost, RateLimitStore store) {
        LocalBatchConfig localBatch = rule.localBatch();

        // CASE 1: Local Batching Disabled -> Delegate write script execution to Store
        if (localBatch == null || !localBatch.enabled()) {
            double refillRatePerMs = (double) rule.limit() / rule.windowSize().toMillis();
            long capacity = rule.effectiveCapacity();
            long now = clock.millis();

            store.commitTokenBucketToPipeline(key, capacity, refillRatePerMs, cost, now);
            return;
        }

        // CASE 2: Local Batching Enabled -> Deduct from memory inventory (0 Redis network calls!)
        LocalTokenBucket localBucket = localBuckets.get(key);
        if (localBucket != null) {
            boolean consumed = localBucket.tryConsumeLocal(cost);
            log.trace("Phase 2 Commit: Consumed {} token(s) locally from memory for key={}. Success={}",
                    cost, key, consumed);
        }
    }

    @Override
    public boolean tryConsume(String key, WindowRule rule, int cost, RateLimitStore store) {
        double refillRatePerMs = (double) rule.limit() / rule.windowSize().toMillis();
        long capacity = rule.effectiveCapacity();
        LocalBatchConfig localBatch = rule.localBatch();

        log.trace("Attempting atomic consumption of cost={} for key={}, strategy=TOKEN_BUCKET, localBatchEnabled={}",
                cost, key, localBatch != null && localBatch.enabled());

        // --- CASE 1: Local Batching Disabled -> Direct Single-Step Redis Consumption ---
        if (localBatch == null || !localBatch.enabled()) {
            long grantedTokens = store.leaseTokenBucket(key, capacity, refillRatePerMs, cost);
            boolean allowed = grantedTokens >= cost;

            if (allowed) {
                log.trace("Successfully consumed {} token(s) directly from Redis for key={}", cost, key);
            } else {
                log.debug("Rate limit exceeded in Redis (local batching disabled) for key={}. Granted {} token(s), needed cost={}",
                        key, grantedTokens, cost);
            }
            return allowed;
        }

        // --- CASE 2: Local Batching Enabled -> Hybrid Local-Redis Leasing ---

        // 1. Get or create local bucket instance configured with rule's properties
        LocalTokenBucket localBucket = localBuckets.computeIfAbsent(
                key,
                k -> new LocalTokenBucket(capacity, refillRatePerMs)
        );

        // 2. Try consuming directly from local memory cache first (zero network I/O)
        if (localBucket.tryConsumeLocal(cost)) {
            log.trace("Successfully consumed {} token(s) locally for key={}", cost, key);
            return true;
        }

        // 3. Local capacity insufficient -> Calculate batch size from rule configuration
        int configuredBatchSize = localBatch.batchSize();
        int batchToRequest = (int) Math.min(capacity, Math.max(cost, configuredBatchSize));

        log.debug("Local bucket depleted for key={}. Requesting token batch of size={} from Redis", key, batchToRequest);

        // 4. Atomically lease token batch from Redis Store
        long grantedTokens = store.leaseTokenBucket(key, capacity, refillRatePerMs, batchToRequest);

        if (grantedTokens >= cost) {
            long surplusTokens = grantedTokens - cost;
            log.debug("Leased {} token(s) from Redis for key={}. Depositing surplus {} token(s) into local bucket",
                    grantedTokens, key, surplusTokens);

            if (surplusTokens > 0) {
                localBucket.depositLeasedTokens(surplusTokens);
            }
            return true;
        }

        // 5. Global limit reached in Redis
        log.debug("Rate limit exceeded in Redis for key={}. Granted {} token(s), required cost={}", key, grantedTokens, cost);
        return false;
    }

    @Override
    public Duration retryAfter(String key, WindowRule rule, RateLimitStore rateLimitStore) {
        log.debug("Calculating retry\\-after duration for key={}", key);
        return rateLimitStore.getTokenBucketRetryAfter(key, rule);
    }
}
