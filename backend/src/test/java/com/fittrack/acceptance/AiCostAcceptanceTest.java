package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AiAssertions;
import com.fittrack.acceptance.support.FakeAiProvider;
import com.fittrack.acceptance.support.FakeAiProvider.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Phase 14 9-10: deterministic cost calculation and cost-absence behaviour.
 *
 * <p>Pricing here is <em>test configuration</em> chosen to make the arithmetic checkable
 * ({@code input = 3.00}, {@code output = 15.00} per million). No production price is asserted
 * or invented.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.ai-limits.pricing.input-per-million=3.00",
        "app.ai-limits.pricing.output-per-million=15.00"
})
class AiCostAcceptanceTest extends AiAssertions {

    private static final BigDecimal INPUT_PRICE = new BigDecimal("3.00");
    private static final BigDecimal OUTPUT_PRICE = new BigDecimal("15.00");
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    private MvcResult coach(Session user) throws Exception {
        return call(user, post("/api/v1/coach/analyze").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private static BigDecimal expectedCost() {
        BigDecimal total = BigDecimal.valueOf(FakeAiProvider.INPUT_TOKENS).multiply(INPUT_PRICE)
                .add(BigDecimal.valueOf(FakeAiProvider.OUTPUT_TOKENS).multiply(OUTPUT_PRICE));
        return total.divide(PER_MILLION, 8, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("Phase 14 item 9 - tokens and the matching estimated cost are persisted together")
    void nonzeroCostIsCalculatedFromConfiguredPricing() throws Exception {
        Session user = register("cost-");
        fake().use(Mode.SUCCESS_WITH_TOKENS_AND_PRICING);

        assertStatus(coach(user), 200);

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat((Integer) row.get("input_tokens")).isEqualTo(FakeAiProvider.INPUT_TOKENS);
        assertThat((Integer) row.get("output_tokens")).isEqualTo(FakeAiProvider.OUTPUT_TOKENS);
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);

        BigDecimal actual = (BigDecimal) row.get("estimated_cost");
        assertThat(actual).as("cost must be persisted when pricing is configured").isNotNull();
        assertThat(actual).isEqualByComparingTo(expectedCost());
        // Explicit arithmetic restated, so the assertion cannot drift with the implementation.
        assertThat(actual).isEqualByComparingTo(new BigDecimal("0.00870000"));
    }

    @Test
    @DisplayName("Phase 14 item 9 - scanner accounting applies the same pricing to food scans")
    void scannerCostUsesTheSameConfiguredPricing() throws Exception {
        Session user = register("cost-scan-");
        fake().use(Mode.SUCCESS_WITH_TOKENS_AND_PRICING);

        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};
        MvcResult result = call(user, multipart("/api/v1/food-scans")
                .file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", jpeg)));
        assertStatus(result, 201);

        Map<String, Object> row = soleUsageRow(user.id(), "food_scan");
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        assertThat((BigDecimal) row.get("estimated_cost")).isEqualByComparingTo(expectedCost());
    }

    @Test
    @DisplayName("Phase 14 item 9 - a provider reporting no tokens must not fabricate a cost")
    void missingTokensProduceNoCost() throws Exception {
        Session user = register("cost-no-tokens-");
        fake().use(Mode.SUCCESS);

        assertStatus(coach(user), 200);

        Map<String, Object> row = soleUsageRow(user.id(), "weekly_coach");
        assertThat(row.get("input_tokens")).isNull();
        assertThat(row.get("estimated_cost")).as("no tokens means no cost").isNull();
    }
}
