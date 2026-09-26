package com.fittrack.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis health indicator.
 *
 * <p>Contributes to overall health only when Redis is actually enabled for rate limiting; when it
 * is not configured the indicator reports {@code UNKNOWN} so it cannot drag the service down.
 * Reports pass/fail only: no host, port or exception detail is exposed, because health output is
 * unauthenticated.
 */
@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;
    private final boolean enabled;

    public RedisHealthIndicator(StringRedisTemplate redis,
            @Value("${app.rate-limit.redis-enabled:false}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        if (!enabled) return Health.unknown().withDetail("redis", "not-configured").build();
        try {
            redis.getConnectionFactory().getConnection().ping();
            return Health.up().withDetail("redis", "reachable").build();
        } catch (Exception e) {
            return Health.down().withDetail("redis", "unreachable").build();
        }
    }
}
