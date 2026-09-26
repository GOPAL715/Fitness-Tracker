package com.fittrack.acceptance;

import com.fittrack.observability.ProductionConfigValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16: production configuration guardrails.
 *
 * <p>Proves the application refuses to start in production with development or default secrets,
 * while local and test configurations are unaffected. The guard is the last line of defence: a
 * deployment that forgets a secret must fail loudly rather than sign tokens with a value that is
 * published in this repository.
 */
class ProductionConfigValidatorTest {

    /** Long enough to satisfy the length rule, and not a real credential. */
    private static final String GOOD_SECRET = "phase16-validator-secret-value-0000000000";

    /**
     * Runs only the validator.
     *
     * <p>Deliberately a plain configuration rather than the whole application: the assertions
     * check the guard's own message, which an unrelated Flyway or JPA startup failure would mask.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubApp.class)
            .withPropertyValues("spring.datasource.url=jdbc:postgresql://db:5432/fittrack",
                    "spring.datasource.username=fittrack", "spring.datasource.password=" + GOOD_SECRET);

    @Test
    @DisplayName("Phase 16 config - a real production secret set starts cleanly")
    void productionWithRealSecretsStarts() {
        runner.withPropertyValues("app.production=true", "app.jwt-secret=" + GOOD_SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("Phase 16 config - a missing production JWT secret fails startup")
    void missingJwtSecretFailsStartup() {
        runner.withPropertyValues("app.production=true", "app.jwt-secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context)).contains("JWT_SECRET");
                });
    }

    @Test
    @DisplayName("Phase 16 config - the shipped development JWT secret is rejected in production")
    void developmentJwtSecretFailsStartup() {
        runner.withPropertyValues("app.production=true",
                        "app.jwt-secret=change-this-development-secret-to-at-least-32-bytes")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The message names the variable, and never echoes the value back.
                    assertThat(stackTraceOf(context)).contains("JWT_SECRET");
                });
    }

    @Test
    @DisplayName("Phase 16 config - a short production JWT secret is rejected")
    void shortJwtSecretFailsStartup() {
        runner.withPropertyValues("app.production=true", "app.jwt-secret=too-short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Phase 16 config - a default database password is rejected in production")
    void defaultDatabasePasswordFailsStartup() {
        runner.withPropertyValues("app.production=true",
                        "app.jwt-secret=" + GOOD_SECRET, "spring.datasource.password=postgres")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // Spring wraps the init failure, so the guard message is checked on the cause.
                    assertThat(stackTraceOf(context)).contains("DATABASE_PASSWORD");
                });
    }

    @Test
    @DisplayName("Phase 16 config - a localhost database URL is rejected in production")
    void localhostDatabaseFailsStartup() {
        runner.withPropertyValues("app.production=true",
                        "app.jwt-secret=" + GOOD_SECRET,
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/fittrack")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context)).contains("DATABASE_URL");
                });
    }

    @Test
    @DisplayName("Phase 16 config - non-production mode is not affected by the guardrails")
    void developmentModeIsUnaffected() {
        runner.withPropertyValues("app.production=false",
                        "app.jwt-secret=change-this-development-secret-to-at-least-32-bytes")
                .run(context -> assertThat(context).hasNotFailed());
    }


    /** The full throwable chain, so a wrapped guard message can still be asserted. */
    private static String stackTraceOf(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
            text.append(cause).append('\n');
        }
        return text.toString();
    }

    @Configuration
    static class StubApp {
        /** Registers the real validator so its own startup guard runs. */
        @Bean
        ProductionConfigValidator validator(
                @Value("${app.production:false}") boolean production,
                @Value("${app.jwt-secret:}") String jwt,
                @Value("${spring.datasource.url:}") String url,
                @Value("${spring.datasource.password:}") String password) {
            return new ProductionConfigValidator(production, jwt, url, password);
        }
    }
}