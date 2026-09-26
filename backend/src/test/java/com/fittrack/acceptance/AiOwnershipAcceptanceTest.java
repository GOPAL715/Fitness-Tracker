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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 14 items 13-14: AI usage ownership isolation and client accounting spoofing.
 *
 * <p>The persisted row must always derive from the JWT subject, the server-side feature name,
 * the actual provider result, and server configuration - never from client input.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class AiOwnershipAcceptanceTest extends AiAssertions {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};

    private MvcResult coach(Session user, String body) throws Exception {
        return call(user, post("/api/v1/coach/analyze").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------------------------------------------------------- item 13 ownership

    @Test
    @DisplayName("Phase 14 item 13 - a user cannot read another user's AI usage")
    void aiUsageIsNotVisibleToAnotherUser() throws Exception {
        Session owner = register("usage-owner-");
        Session other = register("usage-other-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        assertStatus(coach(owner, "{}"), 200);
        UUID ownerUsageId = jdbc.queryForObject(
                "select id from ai_usage where user_id=CAST(? as uuid) and feature='weekly_coach'",
                UUID.class, owner.id());

        MvcResult foreignList = getAs(other, "/api/v1/analytics/ai-usage");
        assertStatus(foreignList, 200);
        assertThat(foreignList.getResponse().getContentAsString())
                .doesNotContain(ownerUsageId.toString())
                .doesNotContain(owner.id());

        MvcResult foreignGet = getAs(other, "/api/v1/ai-usage/" + ownerUsageId);
        assertStatus(foreignGet, 404);
        assertThat(foreignGet.getResponse().getContentAsString()).doesNotContain(FakeAiProvider.SECRET);
    }

    @Test
    @DisplayName("Phase 14 item 13 - a foreign userId parameter cannot widen AI usage visibility")
    void foreignUserIdParameterIsIgnored() throws Exception {
        Session owner = register("usage-param-owner-");
        Session other = register("usage-param-other-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        assertStatus(coach(owner, "{}"), 200);

        MvcResult result = getAs(owner,
                "/api/v1/analytics/ai-usage?user_id=" + other.id() + "&userId=" + other.id());
        assertStatus(result, 200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(owner.id());
        assertThat(body).doesNotContain(other.id());
    }

    @Test
    @DisplayName("Phase 14 item 13 - AI usage endpoints reject unauthenticated callers")
    void aiUsageEndpointsRequireAuthentication() throws Exception {
        assertUnauthenticated(get("/api/v1/analytics/ai-usage"));
        assertUnauthenticated(get("/api/v1/ai-usage"));
    }

    // ------------------------------------------------------ item 14 spoofing

    @Test
    @DisplayName("Phase 14 item 14 - client supplied accounting fields are ignored by the coach")
    void clientSuppliedAccountingFieldsAreIgnored() throws Exception {
        Session attacker = register("spoof-");
        Session victim = register("spoof-victim-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        String hostile = "{\"user_id\":\"" + victim.id() + "\","
                + "\"input_tokens\":999999,\"output_tokens\":999999,\"total_tokens\":1999998,"
                + "\"cost\":1234.56,\"estimated_cost\":1234.56,\"provider\":\"attacker-provider\","
                + "\"billing_amount\":9999.99,\"success\":true,\"feature\":\"attacker_feature\"}";

        assertStatus(coach(attacker, hostile), 200);

        Map<String, Object> row = soleUsageRow(attacker.id(), "weekly_coach");
        assertThat(row.get("user_id").toString()).as("owner comes from the JWT, not the body").isEqualTo(attacker.id());
        assertThat(row.get("feature")).as("feature comes from the server route").isEqualTo("weekly_coach");
        assertThat(row.get("provider")).as("provider comes from the real provider result")
                .isEqualTo(FakeAiProvider.PROVIDER);
        assertThat((Integer) row.get("input_tokens")).isEqualTo(FakeAiProvider.INPUT_TOKENS);
        assertThat((Integer) row.get("output_tokens")).isEqualTo(FakeAiProvider.OUTPUT_TOKENS);
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        assertThat(usageCount(victim.id())).as("no rows may be written for the victim").isZero();
    }

    @Test
    @DisplayName("Phase 14 item 14 - client supplied accounting fields are ignored by the scanner")
    void clientSuppliedScannerFieldsAreIgnored() throws Exception {
        Session attacker = register("spoof-scan-");
        Session victim = register("spoof-scan-victim-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        MvcResult result = call(attacker, multipart("/api/v1/food-scans")
                .file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", JPEG))
                .param("user_id", victim.id())
                .param("input_tokens", "888888")
                .param("output_tokens", "777777")
                .param("total_tokens", "1666665")
                .param("cost", "500.00")
                .param("estimated_cost", "500.00")
                .param("provider", "attacker-provider")
                .param("billing_amount", "42.00"));
        assertStatus(result, 201);

        Map<String, Object> row = soleUsageRow(attacker.id(), "food_scan");
        assertThat(row.get("user_id").toString()).isEqualTo(attacker.id());
        assertThat(row.get("feature")).isEqualTo("food_scan");
        assertThat(row.get("provider")).isEqualTo(FakeAiProvider.PROVIDER);
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        assertThat(usageCount(victim.id())).isZero();
    }

    @Test
    @DisplayName("Phase 14 item 14 - AI usage rows are read-only through the generic resource API")
    void aiUsageCannotBeWrittenOrUpdatedThroughTheResourceApi() throws Exception {
        Session user = register("usage-readonly-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);
        assertStatus(coach(user, "{}"), 200);
        UUID usageId = jdbc.queryForObject(
                "select id from ai_usage where user_id=CAST(? as uuid)", UUID.class, user.id());

        MvcResult create = call(user, post("/api/v1/ai-usage")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"feature\":\"forged\",\"input_tokens\":1,\"success\":true}"));
        assertStatus(create, 403);

        // ai_usage is read-only, so no write verb may mutate a persisted row.
        MvcResult patch = call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/ai-usage/" + usageId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"input_tokens\":42}"));
        assertStatus(patch, 403);

        assertThat(usageCount(user.id())).isEqualTo(1);
        assertThat((Integer) soleUsageRow(user.id(), "weekly_coach").get("input_tokens"))
                .isEqualTo(FakeAiProvider.INPUT_TOKENS);
    }
}
