package com.fittrack.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16: rate limiting semantics.
 *
 * <p>Exercises the boundary logic directly, with Redis disabled, so the exact-limit, over-limit,
 * isolation and reset rules are proven without a Spring context or HTTP timing.
 */
class RateLimitServiceTest {

    /** Redis disabled selects the in-process store, so no connection is ever required. */
    private final RateLimitService limiter = new RateLimitService(null, false);

    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Test
    @DisplayName("Phase 16 rate limit - requests below the limit are allowed")
    void underLimitIsAllowed() {
        String key = unique();
        for (int i = 1; i <= 3; i++) {
            RateLimitService.Decision decision = limiter.hit("api", key, 3, WINDOW, true);
            assertThat(decision.allowed()).as("request %d of 3", i).isTrue();
        }
    }

    @Test
    @DisplayName("Phase 16 rate limit - the exact limit is still allowed, one more is not")
    void exactLimitIsAllowedAndOverLimitIsDenied() {
        String key = unique();
        // The first four are consumed, so the fifth request is the one at the limit.
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.hit("api", key, 5, WINDOW, true).allowed()).isTrue();
        }
        assertThat(limiter.hit("api", key, 5, WINDOW, true).allowed())
                .as("the 5th of 5 requests is exactly at the limit and is allowed").isTrue();
        RateLimitService.Decision over = limiter.hit("api", key, 5, WINDOW, true);
        assertThat(over.allowed()).as("the 6th request exceeds the limit").isFalse();
        assertThat(over.retryAfterSeconds()).isBetween(1L, WINDOW.toSeconds());
    }

    @Test
    @DisplayName("Phase 16 rate limit - two identities never share a bucket")
    void identitiesDoNotShareBuckets() {
        String first = unique();
        String second = unique();
        for (int i = 0; i < 5; i++) limiter.hit("api", first, 5, WINDOW, true);
        assertThat(limiter.hit("api", first, 5, WINDOW, true).allowed()).isFalse();
        assertThat(limiter.hit("api", second, 5, WINDOW, true).allowed())
                .as("an independent user must not be affected by another's traffic").isTrue();
    }

    @Test
    @DisplayName("Phase 16 rate limit - buckets are isolated by name")
    void bucketsAreIsolatedByName() {
        String key = unique();
        for (int i = 0; i < 5; i++) limiter.hit("auth", key, 5, WINDOW, true);
        assertThat(limiter.hit("auth", key, 5, WINDOW, true).allowed()).isFalse();
        assertThat(limiter.hit("api", key, 5, WINDOW, true).allowed())
                .as("AI and auth limits must not consume the general API budget").isTrue();
    }

    @Test
    @DisplayName("Phase 16 rate limit - AI traffic is bounded separately from the general API")
    void aiAndApiLimitsAreIndependent() {
        String key = unique();
        for (int i = 0; i < 3; i++) limiter.hit("ai", key, 3, WINDOW, true);
        assertThat(limiter.hit("ai", key, 3, WINDOW, true).allowed()).isFalse();
        assertThat(limiter.hit("api", key, 300, WINDOW, true).allowed()).isTrue();
    }

    @Test
    @DisplayName("Phase 16 rate limit - a limited bucket still reports remaining budget")
    void remainingIsReported() {
        String key = unique();
        assertThat(limiter.hit("api", key, 10, WINDOW, true).remaining()).isEqualTo(9);
        assertThat(limiter.hit("api", key, 10, WINDOW, true).remaining()).isEqualTo(8);
    }

    @Test
    @DisplayName("Phase 16 rate limit - the in-process store is cleared on sign-out")
    void resetClearsState() {
        String key = unique();
        for (int i = 0; i < 3; i++) limiter.hit("api", key, 3, WINDOW, true);
        assertThat(limiter.hit("api", key, 3, WINDOW, true).allowed()).isFalse();
        limiter.reset();
        assertThat(limiter.hit("api", key, 3, WINDOW, true).allowed())
                .as("state must not leak between sessions on a shared instance").isTrue();
    }

    private static String unique() {
        return "test-" + java.util.UUID.randomUUID();
    }
}
