package com.notificationapp.ratelimiter.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

public class LocalTokenBucket {
    private static final Logger log = LoggerFactory.getLogger(LocalTokenBucket.class);

    private final AtomicLong localTokens = new AtomicLong(0);

    public LocalTokenBucket(long capacity, double refillRatePerMs) {
        log.debug("Initialized LocalTokenBucket with capacity={}", capacity);
    }

    /**
     * Non-mutating inventory check for Phase 1 inspection.
     */
    public boolean hasSufficientTokens(int cost) {
        return localTokens.get() >= cost;
    }

    public long availableTokens() {
        return localTokens.get();
    }

    /**
     * Atomically deposits surplus tokens received from Redis batch leases.
     */
    public void depositLeasedTokens(long tokensToDeposit) {
        localTokens.addAndGet(tokensToDeposit);
        log.debug("Deposited {} tokens into local inventory. Total local tokens: {}",
                tokensToDeposit, localTokens.get());
    }

    /**
     * Phase 2 state mutation: Tries to consume tokens locally using CAS loop.
     */
    public boolean tryConsumeLocal(int cost) {
        while (true) {
            long current = localTokens.get();

            if (current < cost) {
                return false;
            }

            long remaining = current - cost;
            if (localTokens.compareAndSet(current, remaining)) {
                log.debug("Consumed {} token(s) locally. Remaining in local bucket: {}", cost, remaining);
                return true;
            }
        }
    }
}