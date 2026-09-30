package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AiAssertions;
import com.fittrack.acceptance.support.FakeAiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 22 R4/R5: the daily token ceiling is real, and cost is an estimate rather than billing.
 *
 * <p>The ceiling is set to exactly one fake response - input 1200 + output 340 = 1540 tokens - so the
 * boundary is exact. AiUsageService checks the ceiling BEFORE calling the provider, so a first request
 * observes 0 tokens and is admitted, and the second observes 1540 and is refused. No repeated
 * provider calls are needed to reach the limit.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.ai-limits.tokens-per-day=1540",
        "app.ai-limits.requests-per-minute=50",
        "app.ai-limits.requests-per-day=50"
})
class AiTokenCeilingAcceptanceTest extends AiAssertions {

    /**
     * The provider must return token metadata, or nothing accumulates for the ceiling to read.
     *
     * <p>The shared base resets the fake to plain SUCCESS before every test, and that mode reports no
     * usage at all - correct behaviour, and useless for a token-budget test.
     */
    @BeforeEach
    void providerReportsTokens() {
        fake().use(FakeAiProvider.Mode.SUCCESS_WITH_TOKENS_AND_PRICING);
    }

    private MvcResult ask(Session user) throws Exception {
        return call(user, post("/api/v1/coach/insights")
                .contentType(MediaType.APPLICATION_JSON).content("{\"window_days\":7}"));
    }

    @Nested
    @DisplayName("Phase 22 R4 - daily token ceiling")
    class TokenCeiling {

        @Test
        @DisplayName("a request below the ceiling succeeds and records its tokens")
        void belowCeilingSucceeds() throws Exception {
            Session user = register("p22-tok-ok-");
            assertStatus(ask(user), 200);

            assertThat(dailyTokens(user.id()))
                    .as("tokens accumulate for the ceiling to read next time")
                    .isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        }

        @Test
        @DisplayName("a request exceeding the remaining allowance is refused before the provider")
        void exceedingCeilingIsRefused() throws Exception {
            Session user = register("p22-tok-exceed-");
            assertStatus(ask(user), 200);

            assertStructuredError(ask(user), 429, "Too Many Requests");

            assertThat(fake().coachCalls())
                    .as("the refused request must never have been billed to the provider")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the refusal is recorded as a quota rejection, not a provider failure")
        void refusalIsRecordedAsQuota() throws Exception {
            Session user = register("p22-tok-accept-");
            ask(user);
            ask(user);

            List<Map<String, Object>> rows = usageRows(user.id());
            assertThat(rows).hasSize(2);
            Map<String, Object> refusal = rows.stream()
                    .filter(r -> Boolean.FALSE.equals(r.get("success")))
                    .findFirst().orElseThrow();
            assertThat(refusal.get("error_category")).isEqualTo("quota");
            assertThat(refusal.get("total_tokens"))
                    .as("a refused call consumed no tokens").isNull();
        }

        @Test
        @DisplayName("the ceiling is per user")
        void ceilingIsPerUser() throws Exception {
            Session spent = register("p22-tok-spent-");
            Session fresh = register("p22-tok-fresh-");

            ask(spent);
            assertStructuredError(ask(spent), 429, "Too Many Requests");

            assertStatus(ask(fresh), 200);
            assertThat(dailyTokens(fresh.id()))
                    .as("a second account has its own allowance").isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        }

        @Test
        @DisplayName("the ceiling is feature-scoped: scanner budget is untouched")
        void ceilingIsFeatureScoped() throws Exception {
            Session user = register("p22-tok-scope-");
            ask(user);

            assertThat(dayCounter(user.id(), "food_scan"))
                    .as("no scanner request was made, so scanner budget is untouched").isZero();
            assertThat(dayCounter(user.id(), "coach"))
                    .as("only the coach feature consumed its request budget").isEqualTo(1);
        }

        @Test
        @DisplayName("the request-count quota still applies alongside the token ceiling")
        void requestCountQuotaStillApplies() throws Exception {
            Session user = register("p22-count-");
            assertStatus(ask(user), 200);
            assertThat(dayCounter(user.id(), "coach"))
                    .as("the day counter advanced exactly as it did before Phase 22").isEqualTo(1);
            assertThat(minuteCounter(user.id(), "coach"))
                    .as("and so did the minute counter").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Phase 22 R5 - cost is an estimate, not billing")
    class Cost {

        @Test
        @DisplayName("with pricing at zero the cost stays null while tokens persist")
        void zeroPricingKeepsCostNull() throws Exception {
            Session user = register("p22-cost-zero-");
            assertStatus(ask(user), 200);

            Map<String, Object> row = soleUsageRow(user.id(), "coach");
            assertThat(row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
            assertThat(row.get("estimated_cost"))
                    .as("no monetary figure may be invented without configured pricing").isNull();
        }

        @Test
        @DisplayName("cost does not influence whether a request is admitted")
        void costDoesNotAffectQuota() throws Exception {
            Session user = register("p22-cost-quota-");
            assertStatus(ask(user), 200);

            Map<String, Object> row = soleUsageRow(user.id(), "coach");
            assertThat(row.get("success")).isEqualTo(true);
            assertThat(dayCounter(user.id(), "coach")).isEqualTo(1);
        }

        @Test
        @DisplayName("the usage endpoint still reports the row for the caller")
        void usageRemainsObservable() throws Exception {
            Session user = register("p22-cost-usage-");
            assertStatus(ask(user), 200);

            JsonNode usage = json(getAs(user, "/api/v1/analytics/ai-usage"));
            assertThat(usage).hasSize(1);
            assertThat(usage.get(0).path("total_tokens").asInt())
                    .isEqualTo(FakeAiProvider.TOTAL_TOKENS);
            assertThat(usage.get(0).path("estimated_cost").isNull()).isTrue();
        }
    }
}
