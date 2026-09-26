package com.fittrack.health;

import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default health provider, used when no provider integration is configured.
 *
 * <p>FitTrack ships no real wearable integration: Fitbit, Garmin, Apple Health and Google Health
 * Connect each require provider-side OAuth credentials or a native API surface, which are external
 * deployment capabilities. Rather than return fabricated records, this provider reports a stable
 * {@code unavailable} category so the caller sees a normal provider-failure response and no rows
 * are written.
 *
 * <p>A deployment that connects a real provider registers its own {@link HealthProvider} bean and
 * this default yields to it.
 */
@Configuration
public class UnconfiguredHealthProvider {

    private static final String KEY = "unconfigured";

    /**
     * Registered only when no provider is supplied.
     *
     * <p>Declared in a {@code @Configuration} class on purpose: {@code @ConditionalOnMissingBean}
     * is reliable for beans defined in configuration, but on an ordinary {@code @Component} the
     * ordering is undefined, which previously left the application with no provider to inject and
     * unable to start.
     */
    @Bean("unconfiguredHealthProviderBean")
    @ConditionalOnMissingBean(HealthProvider.class)
    HealthProvider unconfiguredHealthProvider() {
        return new HealthProvider() {
            @Override
            public String key() { return KEY; }

            @Override
            public Batch fetch(LocalDate from, LocalDate to, String cursor) {
                // No records are invented. The failure is categorized so the API can respond with
                // a stable error code while never exposing provider internals.
                throw new HealthProviderException(
                        "health provider integration is not configured", HealthProvider.CATEGORY_UNAVAILABLE);
            }
        };
    }
}
