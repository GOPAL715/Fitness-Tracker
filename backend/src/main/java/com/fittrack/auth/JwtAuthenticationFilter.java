package com.fittrack.auth;

import com.fittrack.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates a {@code Bearer} token and places the subject in the security context.
 *
 * <p>Extracted as a named component so the Phase 16 rate limiter can be registered explicitly
 * <em>after</em> this filter. Ordering matters: the limiter keys authenticated traffic by user
 * identity, and that identity only exists once this filter has run.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                String subject = jwt.subject(header.substring(7));
                var authentication = new UsernamePasswordAuthenticationToken(subject, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // An invalid, expired or badly signed token is rejected without disclosing why.
                SecurityContextHolder.clearContext();
                // Same stable contract as every other error, and never why it failed: an
                // attacker learns nothing about token validity beyond "invalid".
                String id = org.slf4j.MDC.get("request_id");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\","
                        + "\"code\":\"invalid_token\",\"message\":\"Invalid or expired token\""
                        + (id == null ? "" : ",\"request_id\":\"" + id + "\"") + "}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
