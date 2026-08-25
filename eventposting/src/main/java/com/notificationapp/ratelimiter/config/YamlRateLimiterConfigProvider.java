package com.notificationapp.ratelimiter.config;

import com.notificationapp.authentication.model.UserTier;
import com.notificationapp.ratelimiter.model.Dimension;
import com.notificationapp.ratelimiter.model.LimitStrategyType;
import com.notificationapp.ratelimiter.model.LocalBatchConfig;
import com.notificationapp.ratelimiter.model.WindowRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(
        name = "rate-limiter.config-source",
        havingValue = "yaml",
        matchIfMissing = true // Defaults to YAML if property is absent
)
public class YamlRateLimiterConfigProvider implements RateLimitConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(YamlRateLimiterConfigProvider.class);

    private final RateLimitProperties properties;

    public YamlRateLimiterConfigProvider(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<WindowRule> rulesFor(UserTier userTier, Dimension dimension) {
        log.debug("Fetching rate limit rules for user tier: {} and dimension: {}", userTier, dimension);

        if (dimension == null) {
            log.warn("Requested rate limit rules with a null dimension");
            return Collections.emptyList();
        }

        // Route dimension resolution to global, ip, or user tier sections
        RateLimitProperties.DimensionSection section = switch (dimension) {
            case GLOBAL -> properties.getGlobal();
            case IP_ADDRESS -> properties.getIp();
            case USER_ID -> resolveUserDimensionSection(userTier);
        };

        if (section == null || section.getWindows() == null || section.getWindows().isEmpty()) {
            log.warn("No YAML window configurations found for dimension: {}. Returning null", dimension);
            return null;
        }

        List<WindowRule> rules = section.getWindows().stream()
                .map(ruleProps -> {
                    LocalBatchConfig localBatchConfig;

                    if (ruleProps.getLocalBatch() != null) {
                        var batch = ruleProps.getLocalBatch();
                        localBatchConfig = new LocalBatchConfig(
                                batch.isEnabled(),
                                batch.getBatchSize(),
                                batch.getRefillThreshold()
                        );
                        log.debug("Mapped LocalBatchConfig [enabled={}, batchSize={}, refillThreshold={}] for strategy {}",
                                localBatchConfig.enabled(),
                                localBatchConfig.batchSize(),
                                localBatchConfig.refillThreshold(),
                                ruleProps.getStrategy());
                    } else {
                        localBatchConfig = LocalBatchConfig.disabled();
                        log.debug("No local-batch config provided for strategy {}. Defaulting to disabled", ruleProps.getStrategy());
                    }

                    return new WindowRule(
                            dimension,
                            ruleProps.getSize(),
                            ruleProps.getLimit(),
                            ruleProps.getStrategy(),
                            ruleProps.getBurstCapacity(),
                            localBatchConfig
                    );
                })
                .collect(Collectors.toList());

        log.debug("Loaded {} YAML rate limit rule(s) for user tier: {} and dimension: {}", rules.size(), userTier, dimension);
        return rules;
    }

    private RateLimitProperties.DimensionSection resolveUserDimensionSection(UserTier userTier) {
        if (properties.getUser() == null || properties.getUser().getTiers() == null) {
            log.warn("User rate limit configuration section or tiers are missing in YAML configuration");
            return null;
        }

        UserTier targetTier = (userTier != null) ? userTier : UserTier.FREE;

        // Fetch requested tier, fall back to FREE tier if key is absent
        RateLimitProperties.DimensionSection section = properties.getUser().getTiers().get(targetTier);
        if (section == null && targetTier != UserTier.FREE) {
            log.warn("No rate limit configuration found for user tier: {}. Falling back to FREE tier", targetTier);
            section = properties.getUser().getTiers().get(UserTier.FREE);
        }

        return section;
    }

    private List<WindowRule> getDefaultFallbackRules(Dimension dimension) {
        log.debug("Generating default fallback rules for dimension: {}", dimension);
        return List.of(new WindowRule(
                dimension,
                Duration.ofMinutes(1),
                20L,
                LimitStrategyType.FIXED_WINDOW,
                null
        ));
    }
}
