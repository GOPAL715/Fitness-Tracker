package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 13: the authenticated push subscription API, against the real database.
 *
 * <p>Push is deliberately left disabled here, so these tests exercise the API surface and its
 * authorization rules without any VAPID configuration. Delivery behaviour is covered separately in
 * {@code com.fittrack.push}.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class PushSubscriptionAcceptanceTest extends AbstractAcceptanceTest {

    /** Structurally valid but inert keys: a 65-byte point and a 16-byte auth secret, per RFC 8291. */
    private static final String P256DH =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]);
    private static final String AUTH =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/acceptance-token";

    @Autowired org.springframework.jdbc.core.JdbcTemplate db;

    private MvcResult register(Session owner, String endpoint, String p256dh, String auth) throws Exception {
        return call(owner, post("/api/v1/push/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "endpoint", endpoint, "p256dh", p256dh, "auth", auth))));
    }

    @Test
    @DisplayName("Phase 13 push - an authenticated user can register a subscription")
    void authenticatedUserCanRegister() throws Exception {
        Session user = register("push-ok-");
        String endpoint = ENDPOINT + "-" + UUID.randomUUID();

        MvcResult result = register(user, endpoint, P256DH, AUTH);

        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        assertThat(json(result).path("endpoint").asText()).isEqualTo(endpoint);
        assertThat(db.queryForObject("select count(*) from push_subscriptions where endpoint=?",
                Integer.class, endpoint)).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 13 push - registration requires authentication")
    void registrationRequiresAuthentication() throws Exception {
        // Scoped to this endpoint rather than the whole table: the shared container is reused across
        // the suite, so a global count would be asserting on other tests' rows.
        String endpoint = ENDPOINT + "-" + UUID.randomUUID();

        MvcResult result = mvc.perform(post("/api/v1/push/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "endpoint", endpoint, "p256dh", P256DH, "auth", AUTH))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(db.queryForObject("select count(*) from push_subscriptions where endpoint=?",
                Integer.class, endpoint)).isZero();
    }

    @Test
    @DisplayName("Phase 13 push - registering twice is idempotent, not a duplicate")
    void registrationIsIdempotent() throws Exception {
        Session user = register("push-idem-");
        String endpoint = ENDPOINT + "-" + UUID.randomUUID();

        register(user, endpoint, P256DH, AUTH);
        MvcResult second = register(user, endpoint, P256DH, AUTH);

        assertThat(second.getResponse().getStatus()).isIn(200, 201);
        assertThat(db.queryForObject("select count(*) from push_subscriptions where endpoint=?",
                Integer.class, endpoint))
                .as("a browser re-subscribes on every load; that must refresh, not accumulate")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 13 push - a client cannot attach a subscription to another user")
    void ownershipCannotBeForged() throws Exception {
        Session victim = register("push-victim-");
        Session attacker = register("push-attacker-");
        String endpoint = ENDPOINT + "-" + UUID.randomUUID();

        // user_id in the body must be ignored; identity comes from the token alone.
        MvcResult forged = call(attacker, post("/api/v1/push/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "user_id", victim.id(), "endpoint", endpoint, "p256dh", P256DH, "auth", AUTH))));

        assertThat(forged.getResponse().getStatus()).isIn(200, 201);
        assertThat(db.queryForObject("select user_id from push_subscriptions where endpoint=?",
                UUID.class, endpoint))
                .as("the row belongs to the caller, never to the user_id in the request")
                .isEqualTo(UUID.fromString(attacker.id()));
    }

    @Test
    @DisplayName("Phase 13 push - invalid endpoints and keys are rejected")
    void invalidInputIsRejected() throws Exception {
        Session user = register("push-invalid-");
        // Every rejected candidate gets a unique endpoint so the final assertion can prove that none
        // of them was persisted, without counting rows other tests created.
        String rejectedEndpoint = ENDPOINT + "-rejected-" + UUID.randomUUID();

        assertStatus(register(user, "http://fcm.googleapis.com/x-" + rejectedEndpoint, P256DH, AUTH), 400);
        assertStatus(register(user, "https://evil.example.com/x-" + rejectedEndpoint, P256DH, AUTH), 400);
        assertStatus(register(user, "https://127.0.0.1/x-" + rejectedEndpoint, P256DH, AUTH), 400);
        assertStatus(register(user, rejectedEndpoint, "not base64!!", AUTH), 400);
        assertStatus(register(user, rejectedEndpoint, P256DH, "short"), 400);

        assertThat(db.queryForObject("select count(*) from push_subscriptions where endpoint=?",
                Integer.class, rejectedEndpoint)).isZero();
    }

    @Test
    @DisplayName("Phase 13 push - a user can delete their own subscription, repeatedly")
    void deletionIsIdempotent() throws Exception {
        Session user = register("push-del-");
        String endpoint = ENDPOINT + "-" + UUID.randomUUID();
        register(user, endpoint, P256DH, AUTH);

        assertStatus(call(user, delete("/api/v1/push/subscriptions").param("endpoint", endpoint)), 200);
        assertThat(db.queryForObject("select count(*) from push_subscriptions where endpoint=?",
                Integer.class, endpoint)).isZero();

        MvcResult again = call(user, delete("/api/v1/push/subscriptions").param("endpoint", endpoint));
        assertStatus(again, 200);
        assertThat(json(again).path("removed").asBoolean())
                .as("a browser unsubscribes locally first, so absence is normal, not an error")
                .isFalse();
    }

    @Test
    @DisplayName("Phase 13 push - a user cannot delete another user's subscription")
    void deletionIsOwnerScoped() throws Exception {
        Session owner = register("push-own-");
        Session other = register("push-other-");
        String endpoint = ENDPOINT + "-" + UUID.randomUUID();
        register(owner, endpoint, P256DH, AUTH);

        assertStatus(call(other, delete("/api/v1/push/subscriptions").param("endpoint", endpoint)), 200);

        assertThat(db.queryForObject("select count(*) from push_subscriptions where endpoint=?",
                Integer.class, endpoint))
                .as("the row survives: the delete was scoped to the wrong user")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 13 push - a user only ever sees their own subscriptions")
    void listingIsOwnerScoped() throws Exception {
        Session owner = register("push-list-own-");
        Session other = register("push-list-other-");
        String ownerEndpoint = ENDPOINT + "-own-" + UUID.randomUUID();
        String otherEndpoint = ENDPOINT + "-other-" + UUID.randomUUID();
        register(owner, ownerEndpoint, P256DH, AUTH);
        register(other, otherEndpoint, P256DH, AUTH);

        MvcResult result = getAs(owner, "/api/v1/push/subscriptions");

        assertStatus(result, 200);
        assertThat(json(result).toString()).contains(ownerEndpoint).doesNotContain(otherEndpoint);
    }

    @Test
    @DisplayName("Phase 13 push - no endpoint ever returns subscription secrets")
    void secretsAreNeverExposed() throws Exception {
        Session user = register("push-secrets-");
        String endpoint = ENDPOINT + "-" + UUID.randomUUID();
        register(user, endpoint, P256DH, AUTH);

        MvcResult config = getAs(user, "/api/v1/push/config");
        for (MvcResult result : new MvcResult[]{getAs(user, "/api/v1/push/subscriptions"), config}) {
            String body = result.getResponse().getContentAsString();
            assertThat(body)
                    .as("p256dh is the decrypting half of the subscription and must never be returned")
                    .doesNotContain(P256DH).doesNotContain(AUTH);
        }

        assertThat(config.getResponse().getContentAsString())
                .as("only the public key is ever handed to a browser")
                .doesNotContain("private");
    }
}
