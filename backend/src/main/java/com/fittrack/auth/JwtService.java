package com.fittrack.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Issues and validates the short-lived access token. */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration access;

    public JwtService(@Value("${app.jwt-secret}") String secret,
            @Value("${app.access-ttl}") Duration access) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.access = access;
    }

    /**
     * Builds a signed access token for {@code user}.
     *
     * <p>The roles claim is snapshotted into an ordinary immutable list before it is attached.
     * {@code User.roles} is a lazy {@code @ElementCollection}, so passing it directly would embed a
     * live Hibernate collection in the token: serializing the token later would then need an open
     * session, and a detached entity fails with a lazy-initialization error surfaced as a 500.
     * Copying the values keeps the token independent of persistence state, and must be done while a
     * session is still open, which is why callers build the token inside a transaction.
     */
    public String access(User user) {
        List<String> roles = List.copyOf(user.getRoles());
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(access)))
                .signWith(key)
                .compact();
    }

    /** The subject (user id) carried by a valid, unexpired, correctly signed token. */
    public String subject(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
    }
}
