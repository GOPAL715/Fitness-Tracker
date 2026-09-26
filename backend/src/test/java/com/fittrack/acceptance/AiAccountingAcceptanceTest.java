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
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 14 2-6: AI usage accounting and provider error normalization.
 *
 * <p>Every assertion is made against the persisted {@code ai_usage} row, never against the
 * HTTP status alone.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class AiAccountingAcceptanceTest extends AiAssertions {

    static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};

    MvcResult coach(Session user) throws Exception {
        return call(user, post("/api/v1/coach/analyze").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    MvcResult scan(Session user) throws Exception {
        return call(user, multipart("/api/v1/food-scans")
                .file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", JPEG)));
    }

    // ------------------------------------------------ 2 successful accounting

    @Test
    @DisplayName("Phase 14 item 2 - provider success persists a complete ai_usage row derived from server state")
    void successfulProviderCallPersistsFullUsageRow() throws Exception {
        Session user = register("ai-success-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);
        String requestId = UUID.randomUUID().toString();

        MvcResult result = call(user, post("/api/v1/coach/analyze")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertStatus(result, 200);
        assertThat(json(result).path("analysis").asText()).isNotBlank();

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("user_id").toString()).as("owner comes from the JWT subject").isEqualTo(user.id());
        assertThat(row.get("feature")).isEqualTo("weekly_coach");
        assertThat(row.get("provider")).isEqualTo(FakeAiProvider.PROVIDER);
        assertThat(row.get("model")).isEqualTo(FakeAiProvider.MODEL);
        assertThat(row.get("request_id").toString()).as("request id is correlated").isEqualTo(requestId);
        assertThat(row.get("success")).isEqualTo(true);
        assertThat((Long) row.get("latency_ms")).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(fake().coachCalls()).isEqualTo(1);

        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo(requestId);
    }

    @Test
    @DisplayName("Phase 14 item 2 - token metadata and totals are persisted when the provider reports usage")
    void tokenMetadataAndTotalsArePersisted() throws Exception {
        Session user = register("ai-tokens-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        assertStatus(coach(user), 200);

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat((Integer) row.get("input_tokens")).isEqualTo(FakeAiProvider.INPUT_TOKENS);
        assertThat((Integer) row.get("output_tokens")).isEqualTo(FakeAiProvider.OUTPUT_TOKENS);
        assertThat((Integer) row.get("total_tokens"))
                .isEqualTo(FakeAiProvider.INPUT_TOKENS + FakeAiProvider.OUTPUT_TOKENS);
        assertThat(dailyTokens(user.id())).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
    }

    @Test
    @DisplayName("Phase 14 item 2 - success without provider usage metadata records no invented tokens")
    void successWithoutMetadataRecordsNoTokens() throws Exception {
        Session user = register("ai-no-tokens-");
        fake().use(Mode.SUCCESS);

        assertStatus(coach(user), 200);

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("input_tokens")).isNull();
        assertThat(row.get("output_tokens")).isNull();
        assertThat(row.get("total_tokens")).isNull();
        assertThat(dailyTokens(user.id())).isZero();
    }

    // ------------------------------------------------------ item 3 provider timeout

    @Test
    @DisplayName("Phase 14 item 3 - provider timeout yields 502, a structured body, a timeout failure row")
    void providerTimeoutIsNormalizedAndAccounted() throws Exception {
        Session user = register("ai-timeout-");
        fake().use(Mode.TIMEOUT);

        assertStructuredError(coach(user), 502, "Bad Gateway");

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("feature")).isEqualTo("weekly_coach");
        assertThat(row.get("error_category")).isEqualTo("timeout");
        assertThat(row.get("success")).isEqualTo(false);
        assertThat(row.get("model")).as("model is server-known even on failure").isEqualTo("fake-text-model");
        assertThat(row.get("total_tokens")).isNull();
        assertThat((Long) row.get("latency_ms")).isNotNull();
        assertThat(fake().coachCalls()).isEqualTo(1);
    }

    // --------------------------------------------------------- item 4 provider 5xx

    @Test
    @DisplayName("Phase 14 item 4 - provider 5xx yields 502 and a provider-category failure row")
    void providerServerErrorIsNormalizedAndAccounted() throws Exception {
        Session user = register("ai-5xx-");
        fake().use(Mode.PROVIDER_SERVER_ERROR);

        assertStructuredError(coach(user), 502, "Bad Gateway");

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("error_category")).isEqualTo("provider");
        assertThat(row.get("success")).isEqualTo(false);
        assertThat(row.get("total_tokens")).isNull();
    }

    // ------------------------------------------- item 5 malformed provider response

    @Test
    @DisplayName("Phase 14 item 5 - malformed provider response is handled deterministically and accounted")
    void malformedProviderResponseIsHandledAndAccounted() throws Exception {
        Session user = register("ai-malformed-");
        fake().use(Mode.MALFORMED_RESPONSE);

        assertStructuredError(coach(user), 502, "Bad Gateway");

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("error_category")).isEqualTo("malformed");
        assertThat(row.get("success")).isEqualTo(false);
        // Deterministic: one request yields exactly one accounting row.
        assertThat(usageCount(user.id())).isEqualTo(1);
    }

    // ------------------------------------------ item 6 application-side exception

    @Test
    @DisplayName("Phase 14 item 6 - an application exception after a successful provider call is accounted")
    void applicationExceptionAfterProviderInteractionIsAccounted() throws Exception {
        Session user = register("ai-app-exception-");
        // The provider succeeds, then the scanner rejects the unusable detection.
        fake().use(Mode.INVALID_FOOD_ITEM);

        MvcResult result = scan(user);
        assertStatus(result, 201);
        assertThat(json(result).path("status").asText()).isEqualTo("failed");

        Map<String, Object> row = soleUsageRow(user.id(), "food_scan");
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("feature")).isEqualTo("food_scan");
        assertThat(row.get("error_category")).as("non-provider failures are categorised").isEqualTo("application");
        assertThat(row.get("success")).isEqualTo(false);
        assertThat(fake().foodCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 14 item 6 - an application error raised on the provider path is categorised as application")
    void providerThrownApplicationErrorIsCategorised() throws Exception {
        Session user = register("ai-app-error-");
        fake().use(Mode.APPLICATION_EXCEPTION);

        assertStructuredError(coach(user), 500, "Internal Server Error");

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("error_category")).isEqualTo("application");
        assertThat(row.get("success")).isEqualTo(false);
    }
}
