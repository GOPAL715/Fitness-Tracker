package com.fittrack.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-window rate limiting with a Redis backend and an in-process fallback.
 *
 * <p><b>Scope.</b> This is transport-level abuse protection. It is deliberately separate from the
 * Phase 14 AI quotas: those limit AI spend per authenticated user, these limit request volume per
 * client. Both must hold.
 *
 * <p><b>Keying.</b> Authenticated requests are keyed by the JWT subject, never by a request-body
 * {@code user_id}, so a caller cannot dodge a limit by claiming a different identity. Anonymous
 * requests are keyed by client address, which is the only boundary available before login.
 *
 * <p><b>Failure mode.</b> When Redis is configured but unreachable the limiter fails
 * <em>closed</em> for authentication endpoints (returns "limited") and open for general API
 * traffic, so an outage cannot lock every user out while brute-force protection is preserved.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final ConcurrentHashMap<String, Window> local = new ConcurrentHashMap<>();

    public RateLimitService(StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value("${app.rate-limit.redis-enabled:false}") boolean redisEnabled) {
        this.redis = redis;
        this.redisEnabled = redisEnabled;
    }

    /** What the caller should do after a decision. */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
        public static Decision allow(long remaining) { return new Decision(true, remaining, 0); }
    }

    /**
     * Records one request against a bucket.
     *
     * @param bucket bucket name, for example {@code auth} or {@code api}
     * @param identity stable client identity (JWT subject, or client address when anonymous)
     * @param limit maximum requests permitted in the window
     * @param window bucket duration
     * @param failClosed when the shared store is unavailable, deny rather than allow
     */
    public Decision hit(String bucket, String identity, int limit, Duration window, boolean failClosed) {
        String key = "rl:" + bucket + ":" + identity;
        long now = System.currentTimeMillis() / 1000;
        long windowStart = now - (now % window.toSeconds());

        if (redisEnabled) {
            try {
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redis.expire(key, window.plusSeconds(1));
                }
                if (count != null && count > limit) {
                    return new Decision(false, 0, Math.max(1, windowStart + window.toSeconds() - now));
                }
                return Decision.allow(Math.max(0, limit - (count == null ? 0 : count)));
            } catch (RuntimeException e) {
                log.warn("rate_limit_store_unavailable bucket={} mode={}", bucket, failClosed ? "closed" : "open");
                if (failClosed) return new Decision(false, 0, window.toSeconds());
            }
        }

        Window state = local.compute(key, (k, existing) -> {
            // A new window starts at 1 (this request); an existing window advances by exactly one.
            if (existing == null || existing.start != windowStart) return new Window(windowStart);
            return existing.advance();
        });
        long count = state.count.get();
        if (count > limit) {
            return new Decision(false, 0, Math.max(1, windowStart + window.toSeconds() - now));
        }
        return Decision.allow(Math.max(0, limit - count));
    }

    /** Current state without consuming budget, used by tests and diagnostics. */
    public Optional<Long> current(String bucket, String identity) {
        if (redisEnabled) {
            try {
                String value = redis.opsForValue().get("rl:" + bucket + ":" + identity);
                return value == null ? Optional.empty() : Optional.of(Long.parseLong(value));
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
        Window window = local.get("rl:" + bucket + ":" + identity);
        return window == null ? Optional.empty() : Optional.of(window.count.get());
    }

    /** Clears all local buckets. Used on sign-out so state cannot leak between accounts. */
    public void reset() { local.clear(); }

    private static final class Window {
        private final long start;
        private final AtomicLong count = new AtomicLong();

        /** A window created by the request that opens it, so it already counts as one. */
        private Window(long start) {
            this.start = start;
            this.count.incrementAndGet();
        }

        /** Advances an open window by exactly one request. */
        private Window advance() {
            this.count.incrementAndGet();
            return this;
        }
    }
}
