package com.fittrack.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Applies request rate limits and production security headers.
 *
 * <p>Buckets are chosen by route so expensive operations get a tighter budget than ordinary reads.
 * The authenticated identity comes from the security context; anonymous traffic is bounded by
 * client address. Neither key can be influenced by request content.
 */
@Component
public class SecurityHeadersRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService limiter;
    private final boolean production;
    private final int authLimit, authWindowSeconds;
    private final int apiLimit, apiWindowSeconds;
    private final int aiLimit, aiWindowSeconds;

    public SecurityHeadersRateLimitFilter(RateLimitService limiter,
            @Value("${app.production:false}") boolean production,
            @Value("${app.rate-limit.auth-requests:10}") int authLimit,
            @Value("${app.rate-limit.auth-window-seconds:60}") int authWindow,
            @Value("${app.rate-limit.api-requests:300}") int apiLimit,
            @Value("${app.rate-limit.api-window-seconds:60}") int apiWindow,
            @Value("${app.rate-limit.ai-requests:20}") int aiLimit,
            @Value("${app.rate-limit.ai-window-seconds:60}") int aiWindow) {
        this.limiter = limiter;
        this.production = production;
        this.authLimit = authLimit;
        this.authWindowSeconds = authWindow;
        this.apiLimit = apiLimit;
        this.apiWindowSeconds = apiWindow;
        this.aiLimit = aiLimit;
        this.aiWindowSeconds = aiWindow;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        applySecurityHeaders(request, response);
        RateLimitService.Decision decision = evaluate(request);
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\",\"code\":\"rate_limited\","
                            + "\"message\":\"Too many requests. Please retry later.\"}");
            return;
        }
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        chain.doFilter(request, response);
    }

    private RateLimitService.Decision evaluate(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Authentication endpoints are the brute-force surface: bounded per client address and
        // failing closed, because an unauthenticated caller has no other stable identity.
        if (path.startsWith("/api/v1/auth/")) {
            return limiter.hit("auth", clientKey(request), authLimit,
                    Duration.ofSeconds(authWindowSeconds), true);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && authentication.getName() != null
                && !"anonymousUser".equals(String.valueOf(authentication.getPrincipal()));
        // AI and scanner traffic is expensive; it gets its own tighter bucket keyed by user.
        if (authenticated && (path.contains("/coach/") || path.contains("/food-scans")
                || path.contains("/complete"))) {
            return limiter.hit("ai", authentication.getName(), aiLimit,
                    Duration.ofSeconds(aiWindowSeconds), false);
        }
        return limiter.hit("api", authenticated ? authentication.getName() : clientKey(request),
                apiLimit, Duration.ofSeconds(apiWindowSeconds), false);
    }

    /** Client address, used only when no authenticated identity exists. */
    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    /**
     * Baseline response hardening.
     *
     * <p>The CSP is deliberately permissive enough for the existing React/Vite build (inline
     * styles and the Vite dev client) while still blocking framing and foreign script sources.
     */
    private void applySecurityHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; connect-src 'self' https: http://localhost:8080; "
                        + "font-src 'self' data: https://fonts.gstatic.com; frame-ancestors 'none'; base-uri 'self'; "
                        + "form-action 'self'; object-src 'none'");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
        if (production && request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }
}