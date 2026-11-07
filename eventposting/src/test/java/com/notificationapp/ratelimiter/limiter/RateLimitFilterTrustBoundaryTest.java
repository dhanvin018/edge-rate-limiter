package com.notificationapp.ratelimiter.limiter;

import com.notificationapp.authentication.model.UserTier;
import com.notificationapp.authentication.services.JwtService;
import com.notificationapp.ratelimiter.config.FallbackPolicy;
import com.notificationapp.ratelimiter.model.RateLimitContext;
import com.notificationapp.ratelimiter.model.RateLimitDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RateLimitFilterTrustBoundaryTest {

    private static final String DOWNSTREAM_URL = "http://downstream.test/events";
    private static final String REMOTE_ADDR = "10.0.0.5";

    private final JwtService jwtService = new JwtService("test-only-secret-with-at-least-32-bytes");
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.evaluate(any())).thenReturn(RateLimitDecision.allow());
        when(rateLimiter.commit(any())).thenReturn(true);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("auth=true: X-User-Id without a token does not authenticate")
    void authModeIgnoresUserIdHeader() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "alice");

        RateLimitContext context = runThroughChain(filter(true, true), request);

        assertThat(context.userId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("auth=true: an invalid JWT does not fall back to X-User-Id")
    void authModeDoesNotFallBackAfterInvalidJwt() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer not-a-valid-token");
        request.addHeader("X-User-Id", "alice");

        RateLimitContext context = runThroughChain(filter(true, true), request);

        assertThat(context.userId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("auth=true: X-User-Tier cannot override the JWT tier")
    void authModeUsesJwtTier() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer " + jwtService.generateToken("bob", "FREE"));
        request.addHeader("X-User-Tier", "PREMIUM");

        RateLimitContext context = runThroughChain(filter(true, true), request);

        assertThat(context.userId()).isEqualTo("bob");
        assertThat(context.userTier()).isEqualTo(UserTier.FREE);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("bob");
    }

    @Test
    @DisplayName("auth=true: X-Forwarded-For is ignored in favour of the remote address")
    void authModeUsesRemoteAddress() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        RateLimitContext context = runThroughChain(filter(true, true), request);

        assertThat(context.ipAddress()).isEqualTo(REMOTE_ADDR);
    }

    @Test
    @DisplayName("auth=true: proxy mode rejects a request with no verified identity")
    void authModeProxyRejectsAnonymous() throws Exception {
        RateLimitFilter filter = filter(true, false);
        MockRestServiceServer downstream = downstreamOf(filter);
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "alice");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        verify(rateLimiter, never()).evaluate(any());
        downstream.verify();
    }

    @Test
    @DisplayName("auth=true: proxy mode forwards verified requests without client identity headers")
    void authModeProxyStripsIdentityHeaders() throws Exception {
        RateLimitFilter filter = filter(true, false);
        MockRestServiceServer downstream = downstreamOf(filter);
        String bearer = "Bearer " + jwtService.generateToken("bob", "FREE");
        downstream.expect(requestTo(DOWNSTREAM_URL))
                .andExpect(header("Authorization", bearer))
                .andExpect(headerDoesNotExist("X-User-Id"))
                .andExpect(headerDoesNotExist("X-User-Tier"))
                .andExpect(headerDoesNotExist("X-Forwarded-For"))
                .andRespond(withSuccess());
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", bearer);
        request.addHeader("X-User-Id", "alice");
        request.addHeader("X-User-Tier", "PREMIUM");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        downstream.verify();
    }

    @Test
    @DisplayName("auth=false: gateway identity headers are trusted")
    void gatewayModeTrustsHeaders() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "alice");
        request.addHeader("X-User-Tier", "PREMIUM");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        RateLimitContext context = runThroughChain(filter(false, true), request);

        assertThat(context.userId()).isEqualTo("alice");
        assertThat(context.userTier()).isEqualTo(UserTier.PREMIUM);
        assertThat(context.ipAddress()).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("auth=false: proxy mode forwards requests with their gateway headers")
    void gatewayModeProxyForwardsHeaders() throws Exception {
        RateLimitFilter filter = filter(false, false);
        MockRestServiceServer downstream = downstreamOf(filter);
        downstream.expect(requestTo(DOWNSTREAM_URL))
                .andExpect(header("X-User-Id", "alice"))
                .andExpect(header("X-User-Tier", "PREMIUM"))
                .andExpect(header("X-Forwarded-For", "203.0.113.9"))
                .andRespond(withSuccess());
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "alice");
        request.addHeader("X-User-Tier", "PREMIUM");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        downstream.verify();
    }

    @Test
    @DisplayName("Actuator requests skip the limiter and are never proxied")
    void actuatorSkipsLimiterAndProxy() throws Exception {
        RateLimitFilter filter = filter(false, false);
        MockRestServiceServer downstream = downstreamOf(filter);
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/actuator/prometheus");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).evaluate(any());
        downstream.verify();
    }

    private RateLimitContext runThroughChain(RateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        ArgumentCaptor<RateLimitContext> context = ArgumentCaptor.forClass(RateLimitContext.class);
        verify(rateLimiter).evaluate(context.capture());
        return context.getValue();
    }

    private RateLimitFilter filter(boolean authenticationRequired, boolean eventPostingEnabled) {
        RateLimitFilter filter = new RateLimitFilter(jwtService, rateLimiter, authenticationRequired, true);
        ReflectionTestUtils.setField(filter, "fallbackPolicy", FallbackPolicy.FAIL_CLOSED);
        ReflectionTestUtils.setField(filter, "eventPostingEnabled", eventPostingEnabled);
        ReflectionTestUtils.setField(filter, "externalDownstreamUrl", DOWNSTREAM_URL);
        return filter;
    }

    private MockRestServiceServer downstreamOf(RateLimitFilter filter) {
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(filter, "restTemplate");
        return MockRestServiceServer.createServer(restTemplate);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/events/postEvent");
        request.setRemoteAddr(REMOTE_ADDR);
        return request;
    }
}
