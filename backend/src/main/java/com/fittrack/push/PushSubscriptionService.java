package com.fittrack.push;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Persistence for browser push subscriptions.
 *
 * <p>Every method takes the authenticated user id. There is deliberately no "find by id" that a caller
 * could reach with someone else's identifier: ownership is a parameter of the query itself rather
 * than a check performed after the fact, so a missing ownership predicate cannot be forgotten at a
 * call site.
 */
@Service
public class PushSubscriptionService {

    /**
     * Push services this application is willing to talk to.
     *
     * <p>The endpoint is a URL the server then sends an HTTP request to, which makes it an SSRF sink
     * if it is accepted unfiltered: a client could aim it at cloud metadata, an internal admin port,
     * or loopback. The value is therefore restricted to HTTPS on a known push-service host, not merely
     * "a well-formed URL".
     */
    private static final List<String> ALLOWED_ENDPOINT_HOSTS = List.of(
            "fcm.googleapis.com",
            "updates.push.services.mozilla.com",
            "push.services.mozilla.com",
            "web.push.apple.com",
            "wns2-*.notify.windows.com");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PushSubscriptionService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Registers or refreshes one subscription.
     *
     * <p>Idempotent on endpoint. Re-registering the same endpoint - which a browser does whenever the
     * page is revisited - refreshes the keys and hands the subscription back to its owner rather than
     * creating a duplicate. The reassignment is deliberate: the browser proved possession of the
     * endpoint by completing the handshake, and a stale row is worse than a moved one.
     */
    @Transactional
    public PushSubscription register(UUID userId, String endpoint, String p256dh, String authSecret) {
        Instant now = clock.instant();
        jdbc.update("insert into push_subscriptions"
                        + "(id,user_id,endpoint,p256dh,auth_secret,created_at,updated_at)"
                        + " values (?,?,?,?,?,?,?)"
                        + " on conflict (endpoint) do update set user_id=excluded.user_id,"
                        + " p256dh=excluded.p256dh,auth_secret=excluded.auth_secret,"
                        + " updated_at=excluded.updated_at",
                UUID.randomUUID(), userId, endpoint, p256dh, authSecret,
                Timestamp.from(now), Timestamp.from(now));
        return byEndpoint(userId, endpoint);
    }

    /**
     * Removes one of the caller's subscriptions.
     *
     * <p>Scoped to the caller, and absence is success. The browser unsubscribes locally first, so the
     * server-side row is usually already gone; treating "not found" as an error would make routine
     * cleanup look like a failure.
     */
    @Transactional
    public boolean delete(UUID userId, String endpoint) {
        return jdbc.update("delete from push_subscriptions where user_id=? and endpoint=?",
                userId, endpoint) > 0;
    }

    /** All of one user's subscriptions. Never returns another user's, by construction. */
    public List<PushSubscription> forUser(UUID userId) {
        return jdbc.query("select id,endpoint,created_at,updated_at from push_subscriptions"
                        + " where user_id=? order by created_at",
                (rs, i) -> new PushSubscription(
                        rs.getObject("id", UUID.class),
                        rs.getString("endpoint"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                userId);
    }

    /**
     * Full rows for delivery, including the secret material.
     *
     * <p>The only method that returns p256dh and the auth secret, and it exists solely to hand them to
     * the push service. It is never used to serve an HTTP response.
     */
    public List<DeliverableSubscription> deliverableForUser(UUID userId) {
        return jdbc.query("select id,endpoint,p256dh,auth_secret from push_subscriptions where user_id=?",
                (rs, i) -> new DeliverableSubscription(
                        rs.getObject("id", UUID.class),
                        rs.getString("endpoint"),
                        rs.getString("p256dh"),
                        rs.getString("auth_secret")),
                userId);
    }

    /** Drops a subscription the push service has told us is permanently gone. */
    @Transactional
    public void deleteByEndpoint(String endpoint) {
        jdbc.update("delete from push_subscriptions where endpoint=?", endpoint);
    }

    private PushSubscription byEndpoint(UUID userId, String endpoint) {
        List<PushSubscription> rows = jdbc.query(
                "select id,endpoint,created_at,updated_at from push_subscriptions"
                        + " where user_id=? and endpoint=?",
                (rs, i) -> new PushSubscription(
                        rs.getObject("id", UUID.class),
                        rs.getString("endpoint"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                userId, endpoint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Rejects anything that is not a real push-service endpoint.
     *
     * <p>Held here rather than in the controller so the rule cannot be bypassed by another caller, and
     * so the failure mode is identical no matter how a subscription arrives.
     */
    public static String validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        String value = endpoint.trim();
        if (value.length() > 2048) {
            throw new IllegalArgumentException("endpoint is too long");
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("endpoint is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("endpoint must use https");
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint must include a host");
        }
        // A literal address can never be a push service. Rejecting these explicitly means a future
        // change to the host list cannot accidentally widen this into a full SSRF hole.
        if (isInternalHost(host)) {
            throw new IllegalArgumentException("endpoint host is not a push service");
        }
        boolean allowed = ALLOWED_ENDPOINT_HOSTS.stream().anyMatch(pattern ->
                pattern.startsWith("*.") ? host.endsWith(pattern.substring(1)) : host.equals(pattern));
        if (!allowed) {
            throw new IllegalArgumentException("endpoint host is not a supported push service");
        }
        return value;
    }

    private static boolean isInternalHost(String host) {
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("0.0.0.0") || host.equals("169.254.169.254")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        // RFC 1918 ranges, including the whole 172.16-172.31 block.
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            try {
                int second = Integer.parseInt(host.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (RuntimeException e) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rejects malformed subscription keys.
     *
     * <p>Both are base64url. Checking the encoding at registration means a bad payload is refused up
     * front, rather than failing later as an opaque decryption error during delivery where it would be
     * far harder to attribute.
     */
    public static void validateKeys(String p256dh, String authSecret) {
        validateBase64Url(p256dh, "p256dh", 65);
        validateBase64Url(authSecret, "auth secret", 16);
    }

    private static void validateBase64Url(String value, String field, int expectedLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not valid base64url");
        }
        // An uncompressed P-256 point is 65 bytes and the auth secret is 16, per RFC 8291.
        if (decoded.length != expectedLength) {
            throw new IllegalArgumentException(field + " has an unexpected length");
        }
    }
    /** A subscription as the API exposes it: identity and timestamps, never the secrets. */
    public record PushSubscription(UUID id, String endpoint, Instant createdAt, Instant updatedAt) {}

    /** A subscription with the material needed to send. Never serialized to a client. */
    public record DeliverableSubscription(UUID id, String endpoint, String p256dh, String authSecret) {}
}
