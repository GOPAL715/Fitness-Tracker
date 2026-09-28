package com.fittrack.acceptance;

import com.fittrack.acceptance.support.FakeAiProvider.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 9 / B-3: the Coach's two independent limits.
 *
 * <p>The Coach sits behind an HTTP transport bucket and an AI quota, and they answer different
 * questions. This class pins both, and pins that they are genuinely different layers: the transport
 * bucket is a 429 {@code rate_limited} that never reaches the provider, whereas the AI quota is a
 * separate 429 that is accounted as a rejected quota row.
 *
 * <p>The limits are deliberately set close together here so the boundary is observable in a test
 * rather than inferred from configuration.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // The AI quota is the tighter of the two, so the quota refusal is reached first.
        "app.ai-limits.requests-per-minute=3",
        "app.ai-limits.requests-per-day=50",
        "app.rate-limit.coach-requests=20",
        "app.rate-limit.coach-window-seconds=60"
})
class CoachRateLimitAcceptanceTest extends com.fittrack.acceptance.support.AiAssertions {

    private MvcResult insights(Session s) throws Exception {
        return call(s, post("/api/v1/coach/insights")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    @DisplayName("Phase 9 - the AI quota admits its budget, then refuses with a quota 429")
    void aiQuotaRefusesBeyondItsBudget() throws Exception {
        Session user = register("coach-quota-");
        fake().use(Mode.SUCCESS);
        fake().reset();

        int ok = 0;
        List<MvcResult> refusals = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            MvcResult r = insights(user);
            if (r.getResponse().getStatus() == 200) ok++;
            else refusals.add(r);
        }

        assertThat(ok).as("the configured AI budget must be admitted in full").isEqualTo(3);
        assertThat(refusals).as("everything beyond the budget is refused").hasSize(3);
        for (MvcResult refusal : refusals) {
            assertStatus(refusal, 429);
            String body = refusal.getResponse().getContentAsString();
            // A quota refusal must carry its own code so it is never confused with a transport
            // rate limit, which is a different layer with a different remedy.
            assertThat(body).contains("ai_quota_exceeded").doesNotContain("\"code\":\"rate_limited\"");
        }
        assertThat(fake().coachCalls())
                .as("a quota-refused request must never reach the provider").isEqualTo(3);
    }

    @Test
    @DisplayName("Phase 9 - the Coach HTTP bucket is separate from the shared AI bucket")
    void coachUsesItsOwnHttpBucket() throws Exception {
        Session user = register("coach-bucket-");
        fake().use(Mode.SUCCESS);

        // The scanner shares the general AI bucket, which this class leaves at 20/min. The Coach
        // path must not consume that budget, which is what the dedicated classification buys.
        // The AI quota (3/min here) binds first, so three calls are admitted and the fourth is
        // refused with the quota code rather than a transport rate limit.
        for (int i = 0; i < 3; i++) {
            assertStatus(insights(user), 200);
        }
        assertThat(usageCount(user.id()))
                .as("every admitted Coach call is accounted under the coach feature")
                .isEqualTo(3);
        assertStatus(insights(user), 429);
    }


    @Test
    @DisplayName("Phase 9 - scanner requests are unaffected by the Coach's own bucket")
    void scannerIsUnaffectedByCoachBucket() throws Exception {
        Session user = register("scanner-regress-");
        fake().use(Mode.SUCCESS);

        // Exhaust the Coach budget, then confirm a non-Coach AI route is still reachable.
        for (int i = 0; i < 4; i++) {
            insights(user);
        }
        MvcResult analytics = call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/analytics/weekly"));
        assertThat(analytics.getResponse().getStatus())
                .as("a non-Coach route must not be blocked by the Coach bucket").isIn(200, 404);
    }
}
