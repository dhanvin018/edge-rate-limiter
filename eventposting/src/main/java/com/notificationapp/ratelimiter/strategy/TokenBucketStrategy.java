package com.notificationapp.ratelimiter.strategy;

import com.notificationapp.ratelimiter.model.LimitStrategyType;
import com.notificationapp.ratelimiter.model.LocalBatchConfig;
import com.notificationapp.ratelimiter.model.WindowRule;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import com.notificationapp.ratelimiter.store.RedisRateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenBucketStrategy implements LimitStrategy {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketStrategy.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisRateLimitStore redisStore;
    private final RedisScript<Long> tokenBucketReadScript;
    private final RedisScript<Long> tokenBucketWriteScript;
    private final Clock clock;

    // Concurrent map keeping active local buckets in memory
    private final ConcurrentHashMap<String, LocalTokenBucket> localBuckets = new ConcurrentHashMap<>();

    public TokenBucketStrategy(StringRedisTemplate redisTemplate,
                               RedisRateLimitStore redisStore,
                               @Qualifier("tokenBucketReadScript") RedisScript<Long> tokenBucketReadScript,
                               @Qualifier("tokenBucketWriteScript") RedisScript<Long> tokenBucketWriteScript,
                               Clock clock) {
        this.redisTemplate = redisTemplate;
        this.redisStore = redisStore;
        this.tokenBucketReadScript = tokenBucketReadScript;
        this.tokenBucketWriteScript = tokenBucketWriteScript;
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

        // CASE 1: Local Batching Disabled -> Direct Redis lease check
        if (localBatch == null || !localBatch.enabled()) {
            return inspectDirectRedis(key, capacity, refillRatePerMs, cost);
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

        // 2. Local inventory depleted -> Lease a full token batch from Redis once
        int configuredBatchSize = localBatch.batchSize();
        int batchToRequest = (int) Math.min(capacity, Math.max(cost, configuredBatchSize));

        log.debug("Local bucket depleted for key={}. Requesting batch size={} from Redis", key, batchToRequest);

        long now = clock.millis();
        Long grantedTokens = 0L;
        try {
            grantedTokens = redisTemplate.execute(
                    tokenBucketWriteScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerMs),
                    String.valueOf(batchToRequest),
                    String.valueOf(now)
            );
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable during token bucket batch lease for key: {}. Failing closed.", key, e);
            return false;
        }

        long granted = (grantedTokens != null) ? grantedTokens : 0L;

        if (granted >= cost) {
            // Immediately stock local memory inventory with the newly leased batch
            localBucket.depositLeasedTokens(granted);
            log.debug("Phase 1: Successfully stocked local inventory with {} tokens for key={}", granted, key);
            return true;
        }

        log.debug("Phase 1: Redis rate limit exceeded for key={}. Granted {} token(s), required cost={}", key, granted, cost);
        return false;
    }

    // =========================================================================
    // PHASE 2: PURE LOCAL MEMORY COMMIT (NO REDIS I/O)
    // =========================================================================
    @Override
    public void commit(String key, WindowRule rule, int cost, RateLimitStore store, RedisConnection connection) {
        LocalBatchConfig localBatch = rule.localBatch();

        // CASE 1: Local Batching Disabled -> Execute write script via pipeline connection
        if (localBatch == null || !localBatch.enabled()) {
            double refillRatePerMs = (double) rule.limit() / rule.windowSize().toMillis();
            long capacity = rule.effectiveCapacity();
            long now = clock.millis();

            connection.evalSha(
                    tokenBucketWriteScript.getSha1(),
                    ReturnType.INTEGER,
                    1,
                    key.getBytes(StandardCharsets.UTF_8),                          // KEYS[1]
                    String.valueOf(capacity).getBytes(StandardCharsets.UTF_8),     // ARGV[1]
                    String.valueOf(refillRatePerMs).getBytes(StandardCharsets.UTF_8),// ARGV[2]
                    String.valueOf(cost).getBytes(StandardCharsets.UTF_8),         // ARGV[3]
                    String.valueOf(now).getBytes(StandardCharsets.UTF_8)          // ARGV[4]
            );
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

    private boolean inspectDirectRedis(String key, long capacity, double refillRatePerMs, int cost) {
        long now = clock.millis();
        Long result = 0L;
        try {
            result = redisTemplate.execute(
                    tokenBucketWriteScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerMs),
                    String.valueOf(cost),
                    String.valueOf(now)
            );
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.error("Redis unavailable while evaluating rate limit for key: {}. Failing closed.", key, e);
            return false;
        }
        return result != null && result >= cost;
    }
    @Override
    public boolean tryConsume(String key, WindowRule rule, int cost, RateLimitStore unusedStore) {
        double refillRatePerMs = (double) rule.limit() / rule.windowSize().toMillis();
        long capacity = rule.effectiveCapacity();
        LocalBatchConfig localBatch = rule.localBatch();

        log.trace("Attempting to consume cost={} for key={}, strategy=TOKEN_BUCKET, localBatchEnabled={}",
                cost, key, localBatch != null && localBatch.enabled());

        // --- CASE 1: Local Batching Disabled -> Direct Redis Consumption ---
        if (localBatch == null || !localBatch.enabled()) {
            long grantedTokens = redisStore.leaseTokenBucket(key, capacity, refillRatePerMs, cost);
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

        // 2. Try consuming from local memory cache first (zero network I/O)
        if (localBucket.tryConsumeLocal(cost)) {
            log.trace("Successfully consumed {} token(s) locally for key={}", cost, key);
            return true;
        }

        // 3. Local capacity insufficient -> Calculate batch size from rule configuration
        int configuredBatchSize = localBatch.batchSize();
        int batchToRequest = (int) Math.min(capacity, Math.max(cost, configuredBatchSize));

        log.debug("Local bucket depleted for key={}. Requesting token batch of size={} from Redis", key, batchToRequest);

        // 4. Atomically lease token batch from Redis
        long grantedTokens = redisStore.leaseTokenBucket(key, capacity, refillRatePerMs, batchToRequest);

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
    public Duration retryAfter(String key, WindowRule rule, RateLimitStore unusedStore) {
        log.debug("Calculating retry\\-after duration for key={}", key);
        return redisStore.getRetryAfter(key, rule);
    }
}
