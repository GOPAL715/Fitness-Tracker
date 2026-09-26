package com.fittrack.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Database readiness indicator.
 *
 * <p>Runs a trivial query and reports only pass/fail. No connection string, credential or
 * exception detail is ever placed in the health payload, because health output is unauthenticated.
 */
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbc;

    public DatabaseHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return Health.up().withDetail("database", "reachable").build();
        } catch (Exception e) {
            return Health.down().withDetail("database", "unreachable").build();
        }
    }
}
