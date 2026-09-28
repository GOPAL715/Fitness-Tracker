package com.fittrack.acceptance;

import com.fittrack.acceptance.support.FakeHealthProvider;
import com.fittrack.acceptance.support.FakeHealthProvider.Mode;
import com.fittrack.acceptance.support.FakeAiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 10 / H-20: the Phase 9 Coach privacy boundary, locked in place.
 *
 * <p>Phase 9 established that Coach context is an allowlist of daily aggregates and never includes
 * device or provider identity. Connecting a health provider creates exactly the kind of data that
 * could leak into it, so the boundary is asserted here rather than trusted: with a real device
 * connected and records imported, the Coach context must still contain aggregates and nothing else.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class CoachHealthPrivacyAcceptanceTest extends com.fittrack.acceptance.support.AiAssertions {

    private static final LocalDate DAY = LocalDate.now().minusDays(2);

    @Test
    @DisplayName("Phase 10 - Coach context excludes every device, provider and credential field")
    void coachContextExcludesHealthProviderIdentity() throws Exception {
        Session user = register("coach-priv-");
        ai().reset();
        ai().use(FakeAiProvider.Mode.SUCCESS);
        provider().reset();
        provider().use(Mode.NORMAL);

        UUID device = UUID.fromString(json(call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_name\":\"Private Band\",\"provider\":\"health-connect\","
                        + "\"external_device_id\":\"ANDROID-DEVICE-ABC123\"}"))).path("id").asText());

        provider().addActivity("priv-" + DAY, DAY, 9500);
        provider().addBody("privb-" + DAY, DAY, "180.0");
        assertStatus(call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);

        assertStatus(call(user, post("/api/v1/coach/insights")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"window_days\":30}")), 200);

        String context = ai().lastCoachContext();
        assertThat(context).as("the Coach must receive some context").isNotBlank();

        for (String forbidden : List.of(
                "health_devices", "external_device_id", "provider_record_id",
                "sync_cursor", "sync_status", "device_id",
                "access_token", "refresh_token", "client_secret",
                "ANDROID-DEVICE-ABC123", "Private Band", "health-connect", "fitbit",
                FakeHealthProvider.SECRET)) {
            assertThat(context)
                    .as("Coach context must never carry %s", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("Phase 10 - Coach may use approved health aggregates, and they are provider-derived here")
    void coachUsesApprovedHealthAggregates() throws Exception {
        Session user = register("coach-agg-");
        ai().reset();
        ai().use(FakeAiProvider.Mode.SUCCESS);
        provider().reset();
        provider().use(Mode.NORMAL);

        UUID device = UUID.fromString(json(call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_name\":\"Band\",\"provider\":\"health-connect\"}"))).path("id").asText());

        // 12000 steps on the day: if the Coach aggregates it, the aggregate is provider-sourced.
        provider().addActivity("agg-" + DAY, DAY, 12000);
        assertStatus(call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);

        assertStatus(call(user, post("/api/v1/coach/insights")
                .contentType(MediaType.APPLICATION_JSON).content("{\"window_days\":30}")), 200);

        String context = ai().lastCoachContext();
        // The Phase 9 activity block: a daily aggregate, never a raw sample.
        assertThat(context).contains("\"steps\"").contains("\"days_logged\"");
        assertThat(context).contains("12000");
    }

    @Test
    @DisplayName("Phase 10 - Coach does not double count a day that has manual and provider rows")
    void coachDoesNotDoubleCountMultiSourceDays() throws Exception {
        Session user = register("coach-dbl-");
        ai().reset();
        ai().use(FakeAiProvider.Mode.SUCCESS);
        provider().reset();
        provider().use(Mode.NORMAL);

        // A manual row and a provider row for the same day. Before Phase 10 this made the Coach
        // sum both, reporting double the real activity.
        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":4000}")), 200);

        UUID device = UUID.fromString(json(call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_name\":\"Band\",\"provider\":\"health-connect\"}"))).path("id").asText());
        provider().addActivity("dbl-" + DAY, DAY, 6000);
        assertStatus(call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);

        assertStatus(call(user, post("/api/v1/coach/insights")
                .contentType(MediaType.APPLICATION_JSON).content("{\"window_days\":30}")), 200);

        String context = ai().lastCoachContext();
        // Exactly two source rows exist for the day; the Coach must see one value, not their sum.
        assertThat(context)
                .as("Coach must not add a manual and a provider row together")
                .doesNotContain("10000");
    }

    /** The health provider fake, which controls what records a sync imports. */
    @org.springframework.beans.factory.annotation.Autowired
    FakeHealthProvider provider;

    /** The AI fake, from which the exact context sent to the model is read. */
    @org.springframework.beans.factory.annotation.Autowired
    FakeAiProvider ai;

    private FakeHealthProvider provider() { return provider; }

    private FakeAiProvider ai() { return ai; }
}
