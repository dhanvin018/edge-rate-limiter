package com.notificationapp.ratelimiter.config;

import com.notificationapp.authentication.model.UserTier;
import com.notificationapp.ratelimiter.model.Dimension;
import com.notificationapp.ratelimiter.model.LimitStrategyType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimitProperties {

    private List<Dimension> dimensionOrder = new ArrayList<>(
            List.of(Dimension.GLOBAL, Dimension.IP_ADDRESS, Dimension.USER_ID)
    );
    private DimensionSection global;
    private DimensionSection ip;
    private UserSection user;

    // --- Top-Level Getters & Setters ---
    public List<Dimension> getDimensionOrder() {
        return dimensionOrder;
    }
    public void setDimensionOrder(List<Dimension> dimensionOrder) {
        this.dimensionOrder = dimensionOrder;
    }

    public DimensionSection getGlobal() {
        return global;
    }
    public void setGlobal(DimensionSection global) {
        this.global = global;
    }

    public DimensionSection getIp() {
        return ip;
    }
    public void setIp(DimensionSection ip) {
        this.ip = ip;
    }

    public UserSection getUser() {
        return user;
    }
    public void setUser(UserSection user) {
        this.user = user;
    }

    // --- Inner Static Classes ---

    public static class DimensionSection {
        private List<WindowRuleProperties> windows;

        public List<WindowRuleProperties> getWindows() {
            return windows;
        }
        public void setWindows(List<WindowRuleProperties> windows) {
            this.windows = windows;
        }
    }

    public static class UserSection {
        private Map<UserTier, DimensionSection> tiers;

        public Map<UserTier, DimensionSection> getTiers() {
            return tiers;
        }
        public void setTiers(Map<UserTier, DimensionSection> tiers) {
            this.tiers = tiers;
        }
    }

    public static class WindowRuleProperties {
        private Duration size;
        private Long limit;
        private LimitStrategyType strategy;
        private Long burstCapacity;
        private LocalBatchProperties localBatch; // ADDED

        public Duration getSize() { return size; }
        public void setSize(Duration size) { this.size = size; }

        public Long getLimit() { return limit; }
        public void setLimit(Long limit) { this.limit = limit; }

        public LimitStrategyType getStrategy() { return strategy; }
        public void setStrategy(LimitStrategyType strategy) { this.strategy = strategy; }

        public Long getBurstCapacity() { return burstCapacity; }
        public void setBurstCapacity(Long burstCapacity) { this.burstCapacity = burstCapacity; }

        public LocalBatchProperties getLocalBatch() { return localBatch; } // ADDED
        public void setLocalBatch(LocalBatchProperties localBatch) { this.localBatch = localBatch; } // ADDED
    }

    // ADD THIS NEW INNER CLASS:
    public static class LocalBatchProperties {
        private boolean enabled = false;
        private int batchSize = 5;
        private int refillThreshold = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public int getRefillThreshold() { return refillThreshold; }
        public void setRefillThreshold(int refillThreshold) { this.refillThreshold = refillThreshold; }
    }
}