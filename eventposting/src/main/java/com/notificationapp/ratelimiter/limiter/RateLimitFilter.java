package com.notificationapp.ratelimiter.limiter;

import com.notificationapp.authentication.model.UserTier;
import com.notificationapp.authentication.services.JwtService;
import com.notificationapp.ratelimiter.config.FallbackPolicy;
import com.notificationapp.ratelimiter.model.RateLimitContext;
import com.notificationapp.ratelimiter.model.RateLimitDecision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    @Value("${rate-limiter.fallback-policy:FAIL_CLOSED}")
    private FallbackPolicy fallbackPolicy;

    @Value("${event.posting.enabled:true}")
    private boolean eventPostingEnabled;

    @Value("${external.downstream.url:}")
    private String externalDownstreamUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final boolean authenticationRequired;
    private final boolean rateLimitingEnabled;

    public RateLimitFilter(
            JwtService jwtService,
            RateLimiter rateLimiter,
            @Value("${authentication.required:true}") boolean authenticationRequired,
            @Value("${rate-limiting.required:false}") boolean rateLimitingEnabled
    ) {
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.authenticationRequired = authenticationRequired;
        this.rateLimitingEnabled = rateLimitingEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        // Skip auth/rate-limiting endpoints
        if (requestUri.startsWith("/api/v1/auth")) {
            log.debug("Bypassing RateLimitFilter for authentication endpoint: {}", requestUri);
            filterChain.doFilter(request, response);
            return;
        }

        String ipAddress = request.getHeader("X-Forwarded-For");
        String authHeader = request.getHeader("Authorization");
        String username = null;
        UserTier userTier = UserTier.FREE;


        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtService.isTokenValid(token)) {
                username = jwtService.extractUsername(token);
                String tierClaim = jwtService.extractTier(token).toString();
                if (tierClaim != null) {
                    try {
                        userTier = UserTier.valueOf(tierClaim.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown tier claim '{}' for user '{}'. Falling back to default tier FREE.", tierClaim, username);
                        userTier = UserTier.FREE;
                    }
                }
                log.debug("Successfully extracted identity for user '{}' with tier '{}' via JWT", username, userTier);
            } else {
                log.warn("Invalid or expired JWT token received from IP: {}", ipAddress);
            }
        }

        if (username == null) {
            // ADDED: Read raw user ID from request header
            username = request.getHeader("X-User-Id");
        }

        String headerTier = request.getHeader("X-User-Tier");
        if (headerTier != null && !headerTier.isBlank()) {
            try {
                // ADDED: Resolve dynamic user tier from request header
                userTier = UserTier.valueOf(headerTier.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown X-User-Tier header value '{}'. Defaulting to FREE.", headerTier);
                userTier = UserTier.FREE;
            }
        }

        if (authenticationRequired) {
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        Collections.emptyList()
                );
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } else if (username == null) {
                log.warn("Authentication required but no valid identity found for request to {}", requestUri);
            }
        } else {
            log.trace("Authentication is disabled by configuration for request to {}", requestUri);
        }

        if(!rateLimitingEnabled){
            log.trace("Rate limiting is disabled by configuration for request to {}", requestUri);
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitContext context = new RateLimitContext(
                username,
                ipAddress,
                userTier,
                1
        );

        log.debug("Evaluating rate limit for request [URI: {}, IP: {}, User: {}, Tier: {}]",
                requestUri, ipAddress, username, userTier);

        RateLimitDecision decision = rateLimiter.evaluate(context);

        if (!decision.allowed()) {
            log.warn("Rate limit exceeded for IP: {}, User: {}, URI: {}. Reason: {}",
                    ipAddress, username, requestUri, decision.reason());
            response.setStatus(429);
            response.getWriter().write(decision.reason().toString());
            return;
        }

        log.trace("Rate limit check passed for request [URI: {}, IP: {}], commiting changes to Redis", requestUri, ipAddress);

        boolean committedSuccessfully = rateLimiter.commit(decision.commitItems());

        if (!committedSuccessfully) {
            if (fallbackPolicy == FallbackPolicy.FAIL_CLOSED) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("text/plain");
                response.getWriter().write("503 Service Unavailable: Rate limiter state commit failed.");
                return;
            } else {
                // FAIL_OPEN: Log warning and allow request to proceed down the filter chain
                log.warn("Fail-Open activated for URI: {}. Request allowed despite limiter commit failure.",
                        request.getRequestURI());
            }
        }

        if (eventPostingEnabled) {
            // Event posting is enabled: continue down the local Spring filter/controller chain
            filterChain.doFilter(request, response);
        } else {
            // Event posting disabled: Forward (proxy) request to external URL
            if (externalDownstreamUrl == null || externalDownstreamUrl.isBlank()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "External downstream URL is not configured.");
                return;
            }
            proxyRequestToExternalUrl(request, response, externalDownstreamUrl);
        }
    }

    private void proxyRequestToExternalUrl(HttpServletRequest request, HttpServletResponse response, String targetUrl) throws IOException {
        // Copy Headers
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (!headerName.equalsIgnoreCase(HttpHeaders.HOST)) { // Exclude host header to prevent target server routing issues
                headers.addAll(headerName, Collections.list(request.getHeaders(headerName)));
            }
        }

        // Copy Body
        byte[] body = request.getInputStream().readAllBytes();
        HttpEntity<byte[]> httpEntity = new HttpEntity<>(body, headers);

        try {
            // Forward HTTP Method, Body, and Headers to the external server
            ResponseEntity<byte[]> externalResponse = restTemplate.exchange(
                    targetUrl,
                    HttpMethod.valueOf(request.getMethod()),
                    httpEntity,
                    byte[].class
            );

            // Copy external response status, headers, and body back to original response
            response.setStatus(externalResponse.getStatusCode().value());
            externalResponse.getHeaders().forEach((key, values) -> {
                for (String value : values) {
                    response.addHeader(key, value);
                }
            });

            if (externalResponse.getBody() != null) {
                response.getOutputStream().write(externalResponse.getBody());
            }
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // Forward error responses from the downstream service
            response.setStatus(e.getStatusCode().value());
            response.getOutputStream().write(e.getResponseBodyAsByteArray());
        }
    }
}