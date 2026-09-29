package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Phase 14: the push subscription rate-limit bucket.
 *
 * <p>A low push limit is set for this context so the boundary is reachable in a test rather than
 * needing hundreds of requests. The tests assert which bucket each operation lands in, that the
 * existing rate-limit response is what comes back, and - just as important - that ordinary API
 * traffic is unaffected and that no request shape can dodge the push bucket.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "app.rate-limit.push-requests=3",
        "app.rate-limit.push-window=60"
})
class PushRateLimitAcceptanceTest extends AbstractAcceptanceTest {

    private static final String P256DH =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]);
    private static final String AUTH =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

    private String endpoint() {
        return "https://fcm.googleapis.com/fcm/send/rl-" + UUID.randomUUID();
    }

    private MvcResult subscribe(Session user, String target) throws Exception {
        return call(user, post("/api/v1/push/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "endpoint", target, "p256dh", P256DH, "auth", AUTH))));
    }

    @Test
    @DisplayName("Phase 14 push - repeated subscription writes are limited, using the existing response")
    void subscriptionWritesAreRateLimited() throws Exception {
        Session user = register("push-rl-");
        assertStatus(subscribe(user, endpoint()), 200);

        // The configured ceiling is 3 writes per window; the registration above used one.
        int limited = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            MvcResult result = subscribe(user, endpoint());
            if (result.getResponse().getStatus() == 429) {
                limited++;
                assertThat(result.getResponse().getHeader("Retry-After"))
                        .as("the project's existing rejection response is returned, not a bespoke one")
                        .isNotNull();
            }
        }

        assertThat(limited)
                .as("the push bucket must stop a caller looping registration")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("Phase 14 push - deletes use the same push bucket as registration")
    void deletesUseTheSameBucket() throws Exception {
        Session user = register("push-rl-del-");
        String target = endpoint();
        assertStatus(subscribe(user, target), 200);

        int limited = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            MvcResult result = call(user, delete("/api/v1/push/subscriptions").param("endpoint", target));
            if (result.getResponse().getStatus() == 429) limited++;
        }

        assertThat(limited)
                .as("a delete must not bypass the bucket that registration is bound by")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("Phase 14 push - the read-only config endpoint is not under the write limit")
    void configReadIsNotLimited() throws Exception {
        Session user = register("push-rl-config-");
        // Well beyond the push ceiling of 3: a cheap read should keep working on the general bucket.
        for (int attempt = 0; attempt < 8; attempt++) {
            assertStatus(getAs(user, "/api/v1/push/config"), 200);
        }
    }

    @Test
    @DisplayName("Phase 14 push - ordinary API traffic keeps its existing bucket")
    void ordinaryApiTrafficIsUnaffected() throws Exception {
        Session user = register("push-rl-api-");
        // Exhaust the push bucket with writes, then prove an unrelated endpoint still answers.
        for (int attempt = 0; attempt < 6; attempt++) {
            subscribe(user, endpoint());
        }
        assertStatus(getAs(user, "/api/v1/reminders/schedule"), 200);
    }

    @Test
    @DisplayName("Phase 14 push - a query-string variant cannot dodge the bucket")
    void endpointVariantsCannotBypass() throws Exception {
        Session user = register("push-rl-bypass-");
        // Same path, different shapes. All must hit the same limited operation rather than falling
        // through to the general bucket.
        for (int attempt = 0; attempt < 8; attempt++) {
            call(user, post("/api/v1/push/subscriptions?attempt=" + attempt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "endpoint", endpoint(), "p256dh", P256DH, "auth", AUTH))));
        }

        assertThat(subscribe(user, endpoint()).getResponse().getStatus())
                .as("the bucket is exhausted, so the next honest write is refused")
                .isEqualTo(429);
    }
}
