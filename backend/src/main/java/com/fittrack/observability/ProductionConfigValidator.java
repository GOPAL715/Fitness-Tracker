package com.fittrack.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Refuses to start a production instance with development or default secrets.
 *
 * <p>Without this, a deployment that forgets {@code JWT_SECRET} would silently sign tokens with a
 * value published in the repository. The check runs only when {@code app.production=true} so
 * local development and the test suite are unaffected. Secret values are never logged - only the
 * name of the offending variable.
 */
@Component
public class ProductionConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    /** The JWT secret shipped in application.yml for local development. */
    private static final String DEV_JWT_SECRET = "change-this-development-secret-to-at-least-32-bytes";

    private final boolean production;
    private final String jwtSecret;
    private final String databaseUrl;
    private final String databasePassword;
    private final int aiTokensPerDay;

    public ProductionConfigValidator(
            @Value("${app.production:false}") boolean production,
            @Value("${app.jwt-secret:}") String jwtSecret,
            @Value("${spring.datasource.url:}") String databaseUrl,
            @Value("${spring.datasource.password:}") String databasePassword,
            @Value("${app.ai-limits.tokens-per-day:0}") int aiTokensPerDay) {
        this.production = production;
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret;
        this.databaseUrl = databaseUrl == null ? "" : databaseUrl;
        this.databasePassword = databasePassword == null ? "" : databasePassword;
        this.aiTokensPerDay = aiTokensPerDay;
    }

    @PostConstruct
    void validate() {
        if (!production) return;
        if (jwtSecret.isBlank() || jwtSecret.equals(DEV_JWT_SECRET) || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least 32 characters in production. Refusing to start.");
        }
        if (databaseUrl.isBlank() || databaseUrl.startsWith("jdbc:postgresql://localhost")) {
            throw new IllegalStateException("DATABASE_URL must point at a real database in production.");
        }
        if (databasePassword.isBlank() || "postgres".equals(databasePassword)) {
            throw new IllegalStateException("DATABASE_PASSWORD must be set in production. Refusing to start.");
        }
        // A non-positive token ceiling means "unlimited", which is the right default for local
        // development and the test suite but is an uncapped bill in production. The check is here
        // rather than in the quota path so that flipping the meaning of zero stays a
        // development-only convenience and never becomes a production bypass.
        if (aiTokensPerDay <= 0) {
            throw new IllegalStateException(
                    "AI_TOKENS_PER_DAY must be a positive token ceiling in production; 0 means "
                            + "unlimited, which is only acceptable outside production. Refusing to start.");
        }
        log.info("production_configuration_validated");
    }
}
