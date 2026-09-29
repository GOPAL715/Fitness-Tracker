package com.fittrack.reminder;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the application's reference clock.
 *
 * <p>Declared as a bean so time is an injected collaborator. Any component that needs "now" takes a
 * {@link Clock} rather than reading the wall clock statically, which is what allows tests to pin an
 * exact instant instead of inheriting whatever time of day the suite happens to run.
 *
 * <p>{@link Clock#systemUTC()} is the default and is behaviourally identical to the
 * {@code Instant.now()} it replaces.
 */
@Configuration
public class ReminderClockConfig {

    @Bean
    public Clock reminderClock() {
        return Clock.systemUTC();
    }
}