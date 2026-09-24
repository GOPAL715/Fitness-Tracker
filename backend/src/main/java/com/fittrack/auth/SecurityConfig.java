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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        var c = new org.springframework.web.cors.CorsConfiguration();
        c.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        c.setAllowCredentials(true);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtService jwt) throws Exception {
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied");
        return http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**", "/actuator/health", "/api/v1/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new Filter() {
                    @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                            throws IOException, ServletException {
                        HttpServletRequest request = (HttpServletRequest) req;
                        HttpServletResponse response = (HttpServletResponse) res;
                        String header = request.getHeader("Authorization");
                        if (header != null && header.startsWith("Bearer ")) {
                            try {
                                String subject = jwt.subject(header.substring(7));
                                var authentication = new UsernamePasswordAuthenticationToken(subject, null, List.of());
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                            } catch (Exception ignored) {
                                SecurityContextHolder.clearContext();
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                                return;
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
