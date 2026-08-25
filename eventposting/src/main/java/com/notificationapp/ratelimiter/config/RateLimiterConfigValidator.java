package com.notificationapp.ratelimiter.config;

import com.notificationapp.authentication.model.UserTier;
import com.notificationapp.ratelimiter.model.LimitStrategyType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RateLimiterConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterConfigValidator.class);
    private final RateLimitProperties properties;

    public RateLimiterConfigValidator(RateLimitProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateAndLogConfiguration() {
        log.info("=================================================");
        log.info(">>> RATE LIMITER PROPERTIES VALIDATION & LOGGING <<<");
        log.info("Dimension Order: {}", properties.getDimensionOrder());

        // Validate Global Rules
        if (properties.getGlobal() != null && properties.getGlobal().getWindows() != null) {
            log.info("GLOBAL Rules Loaded: {} rule(s)", properties.getGlobal().getWindows().size());
            validateWindows("GLOBAL", properties.getGlobal().getWindows());
        } else {
            log.warn("GLOBAL Rules: Not Configured or NULL");
        }

        // Validate IP Rules
        if (properties.getIp() != null && properties.getIp().getWindows() != null) {
            log.info("IP Rules Loaded: {} rule(s)", properties.getIp().getWindows().size());
            validateWindows("IP", properties.getIp().getWindows());
        } else {
            log.warn("IP Rules: Not Configured or NULL");
        }

        // Validate User Tiers Rules
        if (properties.getUser() != null && properties.getUser().getTiers() != null) {
            Map<UserTier, RateLimitProperties.DimensionSection> tiers = properties.getUser().getTiers();
            log.info("USER Tiers Loaded: {}", tiers.keySet());

            tiers.forEach((tier, section) -> {
                if (section != null && section.getWindows() != null) {
                    validateWindows("USER TIER: " + tier, section.getWindows());
                } else {
                    log.warn("USER TIER [{}]: No window rules configured", tier);
                }
            });
        } else {
            log.warn("USER Tiers: Not Configured or NULL");
        }

        log.info(">>> Rate Limiter Configuration Successfully Validated!");
        log.info("=================================================");
    }

    private void validateWindows(String scope, List<RateLimitProperties.WindowRuleProperties> windows) {
        for (int i = 0; i < windows.size(); i++) {
            RateLimitProperties.WindowRuleProperties window = windows.get(i);

            if (window.getStrategy() == null) {
                throw new IllegalStateException(
                        String.format("[%s] Window index [%d]: Strategy is NULL or invalid!", scope, i)
                );
            }

            if (window.getSize() == null || window.getSize().isZero() || window.getSize().isNegative()) {
                throw new IllegalStateException(
                        String.format("[%s] Window index [%d] Strategy [%s]: Invalid window size duration!",
                                scope, i, window.getStrategy())
                );
            }

            if (window.getLimit() == null || window.getLimit() <= 0) {
                throw new IllegalStateException(
                        String.format("[%s] Window index [%d] Strategy [%s]: Limit must be > 0!",
                                scope, i, window.getStrategy())
                );
            }

            if (window.getStrategy() == LimitStrategyType.TOKEN_BUCKET) {
                if (window.getBurstCapacity() == null || window.getBurstCapacity() <= 0) {
                    log.warn("[{}] Window index [{}] Strategy [TOKEN_BUCKET]: burstCapacity not set; fallback to limit [{}]",
                            scope, i, window.getLimit());
                }

                validateLocalBatch(scope, i, window);
            }

            log.info("  -> [{}] Window #{}: size={}, limit={}, strategy={}, burstCapacity={}, localBatch={}",
                    scope,
                    i + 1,
                    window.getSize(),
                    window.getLimit(),
                    window.getStrategy(),
                    window.getBurstCapacity(),
                    formatLocalBatchSummary(window.getLocalBatch()));
        }
    }

    private void validateLocalBatch(String scope, int index, RateLimitProperties.WindowRuleProperties window) {
        RateLimitProperties.LocalBatchProperties localBatch = window.getLocalBatch();

        if (localBatch != null && localBatch.isEnabled()) {
            if (localBatch.getBatchSize() <= 0) {
                throw new IllegalStateException(
                        String.format("[%s] Window index [%d] Strategy [TOKEN_BUCKET]: localBatch.batchSize must be > 0!",
                                scope, index)
                );
            }

            if (localBatch.getRefillThreshold() < 0) {
                throw new IllegalStateException(
                        String.format("[%s] Window index [%d] Strategy [TOKEN_BUCKET]: localBatch.refillThreshold must be >= 0!",
                                scope, index)
                );
            }

            if (localBatch.getRefillThreshold() >= localBatch.getBatchSize()) {
                throw new IllegalStateException(
                        String.format("[%s] Window index [%d] Strategy [TOKEN_BUCKET]: refillThreshold (%d) must be strictly less than batchSize (%d)!",
                                scope, index, localBatch.getRefillThreshold(), localBatch.getBatchSize())
                );
            }
        }
    }

    private String formatLocalBatchSummary(RateLimitProperties.LocalBatchProperties localBatch) {
        if (localBatch == null || !localBatch.isEnabled()) {
            return "disabled";
        }
        return String.format("[enabled=true, batchSize=%d, refillThreshold=%d]",
                localBatch.getBatchSize(), localBatch.getRefillThreshold());
    }
}