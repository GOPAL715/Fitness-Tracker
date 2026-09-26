package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AiAssertions;
import com.fittrack.acceptance.support.FakeAiProvider;
import com.fittrack.acceptance.support.FakeAiProvider.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 14 items 15-16: scanner and coach share one accounting path.
 *
 * <p>Quota is pinned to one request per minute so scanner and coach rejection paths are
 * deterministic against the real PostgreSQL counters.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.ai-limits.requests-per-minute=1",
        "app.ai-limits.requests-per-day=50"
})
class AiFeatureAccountingAcceptanceTest extends AiAssertions {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};

    private MvcResult scan(Session user) throws Exception {
        return call(user, multipart("/api/v1/food-scans")
                .file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", JPEG)));
    }

    private MvcResult coach(Session user) throws Exception {
        return call(user, post("/api/v1/coach/analyze").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    // --------------------------------------------------------- item 15 scanner

    @Test
    @DisplayName("Phase 14 item 15 - a successful scan is accounted against the JWT owner")
    void successfulScanIsAccounted() throws Exception {
        Session user = register("scan-ok-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        MvcResult result = scan(user);
        assertStatus(result, 201);
        assertThat(json(result).path("status").asText()).isEqualTo("completed");

        Map<String, Object> row = soleUsageRow(user.id(), "food_scan");
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("provider")).isEqualTo(FakeAiProvider.PROVIDER);
        assertThat(row.get("model")).isEqualTo(FakeAiProvider.MODEL);
        assertThat(row.get("success")).isEqualTo(true);
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        assertThat(fake().foodCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 14 item 15 - a scan rejected by quota never reaches the provider")
    void scannerQuotaRejectionSkipsTheProvider() throws Exception {
        Session user = register("scan-quota-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        assertStatus(scan(user), 201);
        assertThat(fake().foodCalls()).isEqualTo(1);

        assertStructuredError(scan(user), 429, "Too Many Requests");
        assertThat(fake().foodCalls()).as("a rejected scan must not call the provider").isEqualTo(1);
        assertThat(minuteCounter(user.id(), "food_scan")).isEqualTo(1);

        Map<String, Object> rejection = usageRows(user.id()).stream()
                .filter(r -> Boolean.FALSE.equals(r.get("success")))
                .findFirst().orElseThrow();
        assertThat(rejection.get("user_id").toString()).isEqualTo(user.id());
        assertThat(rejection.get("feature")).isEqualTo("food_scan");
        assertThat(rejection.get("error_category")).isEqualTo("quota");
    }

    @Test
    @DisplayName("Phase 14 item 15 - a provider failure during a scan is accounted and marked failed")
    void scannerProviderFailureIsAccounted() throws Exception {
        Session user = register("scan-fail-");
        fake().use(Mode.PROVIDER_SERVER_ERROR);

        MvcResult result = scan(user);
        assertStatus(result, 201);
        assertThat(json(result).path("status").asText()).isEqualTo("failed");

        Map<String, Object> row = soleUsageRow(user.id(), "food_scan");
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("feature")).isEqualTo("food_scan");
        assertThat(row.get("error_category")).isEqualTo("provider");
        assertThat(row.get("success")).isEqualTo(false);
        assertThat(fake().foodCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 14 item 15 - a scan timeout is categorised as a timeout")
    void scannerTimeoutIsAccounted() throws Exception {
        Session user = register("scan-timeout-");
        fake().use(Mode.TIMEOUT);

        assertStatus(scan(user), 201);
        assertThat(soleUsageRow(user.id(), "food_scan").get("error_category")).isEqualTo("timeout");
    }

    // ----------------------------------------------------------- item 16 coach

    @Test
    @DisplayName("Phase 14 item 16 - a successful coach analysis is accounted against the JWT owner")
    void successfulCoachAnalysisIsAccounted() throws Exception {
        Session user = register("coach-ok-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        MvcResult result = coach(user);
        assertStatus(result, 200);
        assertThat(json(result).path("analysis").asText()).isNotBlank();

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("feature")).isEqualTo("weekly_coach");
        assertThat(row.get("success")).isEqualTo(true);
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        assertThat(fake().coachCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 14 item 16 - a coach request rejected by quota never reaches the provider")
    void coachQuotaRejectionSkipsTheProvider() throws Exception {
        Session user = register("coach-quota-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        assertStatus(coach(user), 200);
        assertThat(fake().coachCalls()).isEqualTo(1);

        assertStructuredError(coach(user), 429, "Too Many Requests");
        assertThat(fake().coachCalls()).as("a rejected coach call must not reach the provider").isEqualTo(1);
        assertThat(minuteCounter(user.id(), "weekly_coach")).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 14 item 16 - a coach provider failure is accounted and returned as 502")
    void coachProviderFailureIsAccounted() throws Exception {
        Session user = register("coach-fail-");
        fake().use(Mode.PROVIDER_SERVER_ERROR);

        assertStructuredError(coach(user), 502, "Bad Gateway");

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("user_id").toString()).as("ownership stays JWT-derived").isEqualTo(user.id());
        assertThat(row.get("error_category")).isEqualTo("provider");
        assertThat(row.get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("Phase 14 item 16 - quota counters are tracked per feature and per user")
    void quotaCountersAreTrackedPerFeature() throws Exception {
        Session user = register("per-feature-");
        Session other = register("per-feature-other-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        // Each feature has its own budget, so a scan does not consume the coach budget.
        assertStatus(scan(user), 201);
        assertStatus(coach(user), 200);
        assertStatus(coach(user), 429);

        assertThat(minuteCounter(user.id(), "food_scan"))
                .as("the scan consumed only the scan budget").isEqualTo(1);
        assertThat(minuteCounter(user.id(), "weekly_coach"))
                .as("only the two coach calls consumed the coach budget").isEqualTo(1);
        assertThat(minuteCounter(other.id(), "food_scan"))
                .as("a second user is unaffected").isZero();
    }
}
