package com.fittrack.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean("fittrackCorsSource") org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource(
            @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:3000}") String configured,
            @org.springframework.beans.factory.annotation.Value("${app.production:false}") boolean production) {
        var c = new org.springframework.web.cors.CorsConfiguration();
        // Origins are explicit, never a wildcard: credentialed requests forbid "*".
        List<String> origins = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (origins.isEmpty()) throw new IllegalStateException("app.cors.allowed-origins must list at least one origin");
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        c.setAllowCredentials(true);
        if (production) c.setMaxAge(3600L);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }

    /**
     * Correlation runs first, ahead of the security chain.
     *
     * <p>Order matters: without it the correlation id is not yet in the MDC when
     * authentication fails, so an authentication error could not be matched to its logs.
     * The security chain itself sits at order -100, hence HIGHEST_PRECEDENCE keeps this in front.
     */
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<com.fittrack.api.RequestCorrelationFilter>
            correlationFilterRegistration() {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<
                com.fittrack.api.RequestCorrelationFilter>();
        registration.setFilter(new com.fittrack.api.RequestCorrelationFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtService jwt, com.fittrack.security.SecurityHeadersRateLimitFilter headers,
            @org.springframework.beans.factory.annotation.Qualifier("fittrackCorsSource") CorsConfigurationSource corsSource) throws Exception {
        // sendError would produce an empty body under this configuration, so authentication
        // failures are written directly in the same stable JSON contract as every other error,
        // including the correlation id so a report can be matched to its log lines.
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
                        "authentication_required", "Authentication required");
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
                        "forbidden", "Access denied");
        return http
                .cors(c -> c.configurationSource(corsSource))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**", "/api/auth/**", "/actuator/health",
                                "/actuator/health/**", "/api/v1/health").permitAll()
                        .anyRequest().authenticated())
                // Order is load-bearing: JWT first so the limiter can key by user identity,
                // then the Phase 16 headers/rate-limit filter, which needs that identity.
                .addFilterBefore(new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(headers, JwtAuthenticationFilter.class)
                .build();
    }

    /**
     * Writes one error in the API's stable JSON contract.
     *
     * <p>Duplicate of the shape produced by GlobalExceptionHandler, because a response sent
     * through the security layer never reaches that advice. The correlation id is attached so
     * an authentication failure can be matched to its log lines.
     */
    private static void writeError(HttpServletResponse response, int status, String error,
            String code, String message) throws java.io.IOException {
        String correlation = org.slf4j.MDC.get("request_id");
        String requestId = correlation == null ? null : (",\"request_id\":\"" + correlation + "\"");
        String body = "{\"status\":" + status + ",\"error\":\"" + error
                + "\",\"code\":\"" + code + "\",\"message\":\"" + message + "\""
                + (requestId == null ? "" : requestId) + "}";
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }

}
