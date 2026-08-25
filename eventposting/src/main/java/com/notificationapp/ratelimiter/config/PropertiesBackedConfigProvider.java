package com.notificationapp.ratelimiter.config;

import com.notificationapp.authentication.model.UserTier;
import com.notificationapp.ratelimiter.model.Dimension;
import com.notificationapp.ratelimiter.model.LocalBatchConfig;
import com.notificationapp.ratelimiter.model.WindowRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@ConditionalOnProperty(
        name = "rate-limiter.config-source",
        havingValue = "properties"
)
public class PropertiesBackedConfigProvider implements RateLimitConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(PropertiesBackedConfigProvider.class);

    private final RateLimitProperties props;

    public PropertiesBackedConfigProvider(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public List<WindowRule> rulesFor(UserTier userTier, Dimension dimension) {
        log.debug("Fetching rate limit rules for user tier: {} and dimension: {}", userTier, dimension);

        if (dimension == null) {
            log.warn("Requested rate limit rules with a null dimension");
            return Collections.emptyList();
        }

        List<RateLimitProperties.WindowRuleProperties> raw = switch (dimension) {
            case GLOBAL -> (props.getGlobal() != null) ? props.getGlobal().getWindows() : null;
            case IP_ADDRESS -> (props.getIp() != null) ? props.getIp().getWindows() : null;
            case USER_ID -> resolveUserWindows(userTier);
        };

        if (raw == null || raw.isEmpty()) {
            log.warn("No properties configuration found for dimension: {}. Returning null", dimension);
            return null;
        }

        List<WindowRule> rules = raw.stream()
                .map(w -> {
                    LocalBatchConfig localBatchConfig;

                    if (w.getLocalBatch() != null) {
                        var batch = w.getLocalBatch();
                        localBatchConfig = new LocalBatchConfig(
                                batch.isEnabled(),
                                batch.getBatchSize(),
                                batch.getRefillThreshold()
                        );
                        log.debug("Mapped LocalBatchConfig [enabled={}, batchSize={}, refillThreshold={}] for strategy {}",
                                localBatchConfig.enabled(),
                                localBatchConfig.batchSize(),
                                localBatchConfig.refillThreshold(),
                                w.getStrategy());
                    } else {
                        localBatchConfig = LocalBatchConfig.disabled();
                        log.debug("No local-batch config provided for strategy {}. Defaulting to disabled", w.getStrategy());
                    }

                    return new WindowRule(
                            dimension,
                            w.getSize(),
                            w.getLimit(),
                            w.getStrategy(),
                            w.getBurstCapacity(),
                            localBatchConfig
                    );
                })
                .toList();

        log.debug("Loaded {} rule(s) for user tier: {} and dimension: {}", rules.size(), userTier, dimension);
        return rules;
    }

    private List<RateLimitProperties.WindowRuleProperties> resolveUserWindows(UserTier userTier) {
        if (props.getUser() == null || props.getUser().getTiers() == null) {
            log.warn("User rate limit configuration section or tiers are missing in properties");
            return null;
        }

        UserTier targetTier = (userTier != null) ? userTier : UserTier.FREE;
        var section = props.getUser().getTiers().get(targetTier);

        if (section == null && targetTier != UserTier.FREE) {
            log.warn("No rate limit configuration found for user tier: {}. Falling back to FREE tier", targetTier);
            section = props.getUser().getTiers().get(UserTier.FREE);
        }

        return (section != null) ? section.getWindows() : null;
    }
}