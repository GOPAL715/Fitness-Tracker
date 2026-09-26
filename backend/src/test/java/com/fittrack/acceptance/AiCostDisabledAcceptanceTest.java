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
 * Phase 14 item 10: cost absence.
 *
 * <p>Pricing is explicitly disabled ({@code 0} per million). Token quantities must still be
 * persisted while the monetary cost stays null - no cost may be fabricated.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.ai-limits.pricing.input-per-million=0",
        "app.ai-limits.pricing.output-per-million=0"
})
class AiCostDisabledAcceptanceTest extends AiAssertions {

    private MvcResult coach(Session user) throws Exception {
        return call(user, post("/api/v1/coach/analyze").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    @DisplayName("Phase 14 item 10 - pricing disabled: tokens persist, cost stays null")
    void tokensPersistButCostStaysNullWhenPricingDisabled() throws Exception {
        Session user = register("no-cost-");
        fake().use(Mode.SUCCESS_WITH_TOKENS_AND_PRICING);

        assertStatus(coach(user), 200);

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat((Integer) row.get("input_tokens"))
                .as("token quantities are still recorded").isEqualTo(FakeAiProvider.INPUT_TOKENS);
        assertThat((Integer) row.get("output_tokens")).isEqualTo(FakeAiProvider.OUTPUT_TOKENS);
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        assertThat(row.get("estimated_cost"))
                .as("no monetary cost may be invented without pricing").isNull();
        assertThat(dailyTokens(user.id())).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
    }

    @Test
    @DisplayName("Phase 14 item 10 - pricing disabled: a scan records tokens with no cost")
    void scannerRecordsTokensWithoutCostWhenPricingDisabled() throws Exception {
        Session user = register("no-cost-scan-");
        fake().use(Mode.SUCCESS_WITH_TOKENS_AND_PRICING);

        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};
        MvcResult result = call(user, multipart("/api/v1/food-scans")
                .file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", jpeg)));
        assertStatus(result, 201);

        Map<String, Object> row = soleUsageRow(user.id(), "food_scan");
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        assertThat(row.get("estimated_cost")).isNull();
    }
}
