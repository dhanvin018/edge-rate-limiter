package com.notificationapp.ratelimiter.limiter;

import com.notificationapp.authentication.model.UserTier;
import com.notificationapp.ratelimiter.config.RateLimitConfigProvider;
import com.notificationapp.ratelimiter.model.Dimension;
import com.notificationapp.ratelimiter.model.LimitStrategyType;
import com.notificationapp.ratelimiter.model.RateLimitContext;
import com.notificationapp.ratelimiter.model.RateLimitDecision;
import com.notificationapp.ratelimiter.model.RedisCommitModel;
import com.notificationapp.ratelimiter.model.WindowRule;
import com.notificationapp.ratelimiter.store.RateLimitStore;
import com.notificationapp.ratelimiter.strategy.LimitStrategy;
import com.notificationapp.ratelimiter.strategy.StrategyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationReleaseTest {

    private final RateLimitStore store = mock(RateLimitStore.class);
    private final LimitStrategy strategy = mock(LimitStrategy.class);
    private final RateLimitContext context = new RateLimitContext(null, null, UserTier.FREE, 1);
    private final WindowRule firstRule = rule(Duration.ofSeconds(1));
    private final WindowRule secondRule = rule(Duration.ofSeconds(2));

    @Test
    @DisplayName("A rejection by a later rule releases reservations from earlier rules in the same dimension")
    void dimensionReleasesEarlierRules() {
        RateLimitConfigProvider configProvider = mock(RateLimitConfigProvider.class);
        StrategyRegistry registry = mock(StrategyRegistry.class);
        when(configProvider.rulesFor(UserTier.FREE, Dimension.GLOBAL)).thenReturn(List.of(firstRule, secondRule));
        when(registry.get(LimitStrategyType.TOKEN_BUCKET)).thenReturn(strategy);
        when(strategy.canConsume(anyString(), any(), anyInt(), any())).thenReturn(true, false);
        when(strategy.retryAfter(anyString(), any(), any())).thenReturn(Duration.ZERO);
        DimensionLimiter limiter = new DimensionLimiter(Dimension.GLOBAL, configProvider, store, registry);

        RateLimitDecision decision = limiter.evaluate(context);

        assertThat(decision.allowed()).isFalse();
        verify(strategy).release("rl:global:*:TOKEN_BUCKET:PT1S", firstRule, 1, store);
        verify(strategy, never()).release("rl:global:*:TOKEN_BUCKET:PT2S", secondRule, 1, store);
    }

    @Test
    @DisplayName("A rejection by a later dimension releases reservations from dimensions that passed")
    void compositeReleasesPassedDimensions() {
        RedisCommitModel passed = new RedisCommitModel("rl:user_id:alice:TOKEN_BUCKET:PT1S", firstRule, strategy, 1);
        DimensionLimiter userLimiter = mock(DimensionLimiter.class);
        DimensionLimiter ipLimiter = mock(DimensionLimiter.class);
        when(userLimiter.evaluate(context)).thenReturn(RateLimitDecision.allowed(List.of(passed)));
        when(ipLimiter.evaluate(context)).thenReturn(RateLimitDecision.rateExceeded("ip", Duration.ZERO, "denied"));
        CompositeRateLimiter limiter = new CompositeRateLimiter(List.of(userLimiter, ipLimiter), store);

        RateLimitDecision decision = limiter.evaluate(context);

        assertThat(decision.allowed()).isFalse();
        verify(strategy).release(passed.key(), firstRule, 1, store);
    }

    private static WindowRule rule(Duration window) {
        return new WindowRule(Dimension.GLOBAL, window, 10, LimitStrategyType.TOKEN_BUCKET, null);
    }
}
