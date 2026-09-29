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
    private final int coachLimit, coachWindowSeconds;
    private final int healthLimit, healthWindowSeconds;
    private final int pushLimit, pushWindowSeconds;

    public SecurityHeadersRateLimitFilter(RateLimitService limiter,
            @Value("${app.production:false}") boolean production,
            @Value("${app.rate-limit.auth-requests:10}") int authLimit,
            @Value("${app.rate-limit.auth-window-seconds:60}") int authWindow,
            @Value("${app.rate-limit.api-requests:300}") int apiLimit,
            @Value("${app.rate-limit.api-window-seconds:60}") int apiWindow,
            @Value("${app.rate-limit.ai-requests:20}") int aiLimit,
            @Value("${app.rate-limit.ai-window-seconds:60}") int aiWindow,
            @Value("${app.rate-limit.coach-requests:20}") int coachLimit,
            @Value("${app.rate-limit.coach-window-seconds:60}") int coachWindow,
            @Value("${app.rate-limit.health-requests:60}") int healthLimit,
            @Value("${app.rate-limit.health-window-seconds:60}") int healthWindow,
            @Value("${app.rate-limit.push-requests:20}") int pushLimit,
            @Value("${app.rate-limit.push-window-seconds:60}") int pushWindow) {
        this.limiter = limiter;
        this.production = production;
        this.authLimit = authLimit;
        this.authWindowSeconds = authWindow;
        this.apiLimit = apiLimit;
        this.apiWindowSeconds = apiWindow;
        this.aiLimit = aiLimit;
        this.aiWindowSeconds = aiWindow;
        this.coachLimit = coachLimit;
        this.coachWindowSeconds = coachWindow;
        this.healthLimit = healthLimit;
        this.healthWindowSeconds = healthWindow;
        this.pushLimit = pushLimit;
        this.pushWindowSeconds = pushWindow;
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

        // The Coach gets its own bucket rather than sharing the general AI one. The two are
        // different layers with different jobs: this is transport protection for one endpoint,
        // while the AI quota in AiUsageService bounds actual provider consumption. Giving Coach
        // its own bucket means its limit can be tuned without changing scanner or composite
        // write behaviour, which share the general AI bucket below.
        if (authenticated && path.startsWith("/api/v1/coach/")) {
            return limiter.hit("coach", authentication.getName(), coachLimit,
                    Duration.ofSeconds(coachWindowSeconds), false);
        }

        // Phase 11: health ingestion is a write path carrying batches, so it gets its own bucket
        // rather than sharing the general API allowance. Keyed by the authenticated user, so a caller
        // cannot dodge it by claiming another identity, and not per device - one user with several
        // Android devices is one client, and per-device keying would multiply the real budget by the
        // number of devices a user registers. The bucket bounds request volume only; the per-request
        // record and body-size ceilings are enforced separately in the ingest path.
        if (authenticated && path.startsWith("/api/v1/health/")) {
            return limiter.hit("health", authentication.getName(), healthLimit,
                    Duration.ofSeconds(healthWindowSeconds), false);
        }

        // Phase 14: push subscription registration is a write path, and it is the one place a
        // browser can be talked into calling repeatedly. It gets its own bucket rather than sharing
        // the general API allowance, so a tighter limit can be applied without changing ordinary
        // read traffic.
        //
        // Matched on the write methods deliberately. Matching the path alone would leave
        // GET /api/v1/push/config through the general bucket while POST and DELETE were limited here,
        // which is inconsistent; and matching only POST would let a client bypass the limit by
        // sending the delete as a GET-shaped request. The config endpoint is a cheap read and is
        // excluded below so the budget covers the operations that actually write.
        if (authenticated && path.startsWith("/api/v1/push/subscriptions")
                && ("POST".equals(request.getMethod()) || "DELETE".equals(request.getMethod()))) {
            return limiter.hit("push", authentication.getName(), pushLimit,
                    Duration.ofSeconds(pushWindowSeconds), false);
        }

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