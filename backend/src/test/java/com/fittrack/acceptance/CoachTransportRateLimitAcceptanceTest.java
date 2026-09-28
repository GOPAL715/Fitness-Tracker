package com.fittrack.acceptance;

import com.fittrack.acceptance.support.FakeAiProvider.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 9 / B-3: the Coach transport bucket, isolated from the AI quota.
 *
 * <p>Its own Spring context, because the two limits are deliberately configured in opposite
 * directions: a one-request transport budget against a generous AI quota. That is the only way to
 * show which layer produced a refusal, rather than merely that some 429 happened.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Transport binds first here, so the AI quota is set high enough never to interfere.
        "app.rate-limit.coach-requests=1",
        "app.rate-limit.coach-window-seconds=60",
        "app.ai-limits.requests-per-minute=50",
        "app.ai-limits.requests-per-day=500"
})
class CoachTransportRateLimitAcceptanceTest extends com.fittrack.acceptance.support.AiAssertions {

    private MvcResult insights(Session s) throws Exception {
        return call(s, post("/api/v1/coach/insights")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    @DisplayName("Phase 9 - a transport refusal is rate_limited, never ai_quota_exceeded")
    void transportRefusalUsesItsOwnCode() throws Exception {
        Session user = register("coach-transport-");
        fake().use(Mode.SUCCESS);

        assertStatus(insights(user), 200);
        MvcResult limited = insights(user);

        assertStatus(limited, 429);
        assertThat(limited.getResponse().getContentAsString())
                .as("the transport layer owns the rate_limited code")
                .contains("rate_limited")
                .doesNotContain("ai_quota_exceeded");
        assertThat(limited.getResponse().getHeader("Retry-After"))
                .as("a transport refusal must tell the client when to retry").isNotBlank();
        assertThat(fake().coachCalls())
                .as("a transport refusal must never reach the provider").isEqualTo(1);
        assertThat(usageCount(user.id()))
                .as("a transport refusal is not an accounted AI attempt").isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 9 - the Coach bucket is per user, so one user cannot exhaust another's")
    void transportBucketIsScopedPerUser() throws Exception {
        Session first = register("coach-transport-a-");
        Session second = register("coach-transport-b-");
        fake().use(Mode.SUCCESS);

        assertStatus(insights(first), 200);
        assertStatus(insights(first), 429);
        assertStatus(insights(second), 200);
    }

    @Test
    @DisplayName("Phase 9 - a non-Coach route is not blocked by the Coach's own bucket")
    void otherRoutesAreNotBlocked() throws Exception {
        Session user = register("coach-transport-c-");
        fake().use(Mode.SUCCESS);

        // Exhaust the Coach transport budget.
        assertStatus(insights(user), 200);
        assertStatus(insights(user), 429);

        MvcResult analytics = call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/analytics/weekly"));
        assertThat(analytics.getResponse().getStatus())
                .as("a non-Coach route must not be blocked by the Coach bucket").isIn(200, 404);
    }
}
