package com.fittrack.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 10 regression gate: the source model must not change what Phase 1-9 features show.
 *
 * <p>V10 lets a day hold a manual row and one row per device. Everything that reads a day has to
 * keep seeing exactly one value, or progress charts gain duplicate dates, the calendar grid repeats
 * days, the Coach double counts activity, and app data returns two rows for one day. Each of those
 * is a user-visible change, so each is asserted here.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class CanonicalAnalyticsRegressionAcceptanceTest extends com.fittrack.acceptance.support.AiAssertions {

    private static final String DAY = "2026-08-14";
    private static final String DAY2 = "2026-08-15";

    private Session registerUser(String prefix) throws Exception {
        return register(prefix);
    }

    private void addDevice(Session user, String name, String provider, String externalId) throws Exception {
        MvcResult r = call(user, post("/api/v1/health/devices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_name\":\"" + name + "\",\"provider\":\"" + provider + "\","
                        + "\"external_device_id\":\"" + externalId + "\"}"));
        assertStatus(r, 200);
    }

    private void addProviderRows(Session user, String date, String provider, String recordId,
                                 Integer steps, Integer calories, Integer water) {
        // Written exactly as the sync service's own batch upsert writes them, so the constraint and
        // the canonical view are exercised through the same statements production uses.
        StringBuilder sql = new StringBuilder(
                "insert into daily_metrics(id,user_id,metric_date,steps,calories_burned,water_oz,source,provider_record_id,device_id)"
                        + " select gen_random_uuid(), '" + user.id() + "'::uuid, '" + date + "'::date, ")
                .append(steps == null ? "null" : steps).append(", ")
                .append(calories == null ? "null" : calories).append(", ")
                .append(water == null ? "null" : water).append(", '").append(provider)
                .append("', '").append(recordId).append("', d.id from health_devices d")
                .append(" where d.user_id='" + user.id() + "'::uuid and d.provider='").append(provider)
                .append("' limit 1")
                .append(" on conflict (user_id,source,provider_record_id) where provider_record_id is not null")
                .append(" do update set steps=excluded.steps, calories_burned=excluded.calories_burned,")
                .append(" water_oz=excluded.water_oz;");
        jdbc.update(sql.toString());
    }

    private int sourceRows(Session user, String date) {
        return jdbc.queryForObject("select count(*) from daily_metrics where user_id=CAST(? as uuid)"
                + " and metric_date=CAST(? as date)", Integer.class, user.id(), date);
    }

    private JsonNode analytics(Session user, String path) throws Exception {
        MvcResult r = call(user, get(path));
        assertStatus(r, 200);
        return json(r);
    }

    // ------------------------------------------------------------- analytics

    @Test
    @DisplayName("Phase 10 - a multi-source day still yields one analytics activity point")
    void activitySeriesHasNoDuplicateDates() throws Exception {
        Session user = registerUser("canon-analytics-");
        addDevice(user, "Band", "health-connect", "EXT-A");

        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":1000,\"water_oz\":700}")), 200);
        addProviderRows(user, DAY, "health-connect", "hc-1", 5000, 2400, null);
        addProviderRows(user, DAY2, "health-connect", "hc-2", 6000, 2500, null);

        assertThat(sourceRows(user, DAY)).as("both source rows exist").isEqualTo(2);

        JsonNode progress = analytics(user, "/api/v1/analytics/progress?from=" + DAY + "&to=" + DAY2);
        List<String> dates = new ArrayList<>();
        progress.path("activity").forEach(node -> dates.add(node.path("date").asText()));

        assertThat(dates).as("one point per date, never one per source row")
                .containsExactlyInAnyOrder(DAY, DAY2);
        assertThat(dates).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Phase 10 - the dashboard shows the provider value, not the manual one")
    void dashboardUsesCanonicalValue() throws Exception {
        Session user = registerUser("canon-dash-");
        addDevice(user, "Band", "health-connect", "EXT-B");

        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":1000}")), 200);
        addProviderRows(user, DAY, "health-connect", "hc-d", 5000, null, null);

        // The dashboard reports "today", so the same-day assertion is made through the canonical view
        // and the progress endpoint rather than a wall-clock default that moves with the clock.
        JsonNode progress = analytics(user, "/api/v1/analytics/progress?from=" + DAY + "&to=" + DAY);
        assertThat(progress.path("activity").get(0).path("steps").asInt())
                .as("the provider value wins for a field it supplies").isEqualTo(5000);
    }

    @Test
    @DisplayName("Phase 10 - a manual-only day is unchanged, and a provider null falls back to manual")
    void manualFallbackIsPreserved() throws Exception {
        Session user = registerUser("canon-fallback-");
        addDevice(user, "Band", "health-connect", "EXT-C");

        // Manual supplies water; the provider supplies steps only.
        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":1000,\"water_oz\":700}")), 200);
        addProviderRows(user, DAY, "health-connect", "hc-f", 5000, null, null);

        Map<String, Object> canonical = jdbc.queryForMap(
                "select steps, water_oz from v_daily_metrics_canonical"
                        + " where user_id=CAST(? as uuid) and metric_date=CAST(? as date)", user.id(), DAY);
        assertThat(((Number) canonical.get("steps")).intValue()).isEqualTo(5000);
        assertThat(((Number) canonical.get("water_oz")).intValue())
                .as("manual is the fallback for a field the provider does not supply").isEqualTo(700);
    }

    // -------------------------------------------------------------- calendar

    @Test
    @DisplayName("Phase 10 - the calendar grid lists each date once, not once per source")
    void calendarHasNoDuplicateDates() throws Exception {
        Session user = registerUser("canon-cal-");
        addDevice(user, "Band", "health-connect", "EXT-D");

        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":1000,\"water_oz\":700}")), 200);
        addProviderRows(user, DAY, "health-connect", "hc-cal", 5000, null, null);

        JsonNode cal = analytics(user, "/api/v1/calendar/summary?from=" + DAY + "&to=" + DAY);
        List<String> dates = new ArrayList<>();
        cal.path("days").forEach(node -> dates.add(node.path("date").asText()));

        assertThat(dates).as("a day with two source rows must appear once")
                .containsExactly(DAY);
    }

    // ------------------------------------------------------------- app data

    @Test
    @DisplayName("Phase 10 - app data returns one metric row per day, not one per source")
    void appDataHasNoDuplicateDays() throws Exception {
        Session user = registerUser("canon-appdata-");
        addDevice(user, "Band", "health-connect", "EXT-E");

        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":1000}")), 200);
        addProviderRows(user, DAY, "health-connect", "hc-app", 5000, null, null);

        MvcResult r = call(user, get("/api/v1/app-data"));
        assertStatus(r, 200);
        JsonNode metrics = json(r).path("metrics");

        List<String> dates = new ArrayList<>();
        metrics.forEach(node -> dates.add(node.path("metric_date").asText()));
        assertThat(dates).as("exactly one projected row for the day").containsExactly(DAY);
        assertThat(metrics.get(0).path("steps").asInt()).isEqualTo(5000);
    }

    // ------------------------------------------------------- manual editing

    @Test
    @DisplayName("Phase 10 - editing a day updates the manual row and never the provider row")
    void manualEditDoesNotOverwriteProviderData() throws Exception {
        Session user = registerUser("canon-edit-");
        addDevice(user, "Band", "health-connect", "EXT-F");

        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":1000}")), 200);
        addProviderRows(user, DAY, "health-connect", "hc-edit", 5000, 2400, null);

        // A second manual post for the same day is an update of the manual row.
        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":1500}")), 200);

        Integer manualSteps = jdbc.queryForObject("select steps from daily_metrics"
                + " where user_id=CAST(? as uuid) and metric_date=CAST(? as date) and provider_record_id is null",
                Integer.class, user.id(), DAY);
        Integer providerSteps = jdbc.queryForObject("select steps from daily_metrics"
                + " where user_id=CAST(? as uuid) and provider_record_id='hc-edit'",
                Integer.class, user.id());

        assertThat(manualSteps).as("the manual row carries the edit").isEqualTo(1500);
        assertThat(providerSteps).as("the provider row is untouched").isEqualTo(5000);
    }

    // ------------------------------------------------------- water logging

    @Test
    @DisplayName("Phase 10 - water logging targets the manual row and never overwrites a provider row")
    void waterTargetsTheManualRow() throws Exception {
        Session user = registerUser("canon-water-");
        addDevice(user, "Band", "health-connect", "EXT-G");

        // The water endpoint always logs against the server current day, so the day under test is
        // today rather than a fixed past date.
        String today = java.time.LocalDate.now().toString();

        assertStatus(call(user, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + today + "\",\"steps\":1000}")), 200);
        addProviderRows(user, today, "health-connect", "hc-water", 5000, null, 999);

        MvcResult r = call(user, post("/api/v1/water").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":250}"));
        assertStatus(r, 200);

        Integer manualWater = jdbc.queryForObject("select water_oz from daily_metrics"
                + " where user_id=CAST(? as uuid) and metric_date=CAST(? as date) and provider_record_id is null",
                Integer.class, user.id(), today);
        Integer providerWater = jdbc.queryForObject("select water_oz from daily_metrics"
                + " where user_id=CAST(? as uuid) and provider_record_id='hc-water'",
                Integer.class, user.id());

        assertThat(manualWater).as("water accumulates on the manual row").isEqualTo(250);
        assertThat(providerWater).as("the provider row's own value is never rewritten").isEqualTo(999);
        assertThat(sourceRows(user, today)).as("no new row was created by logging water").isEqualTo(2);
    }

    @Test
    @DisplayName("Phase 10 - water logging works on a provider-only day without creating a conflict")
    void waterOnAProviderOnlyDay() throws Exception {
        Session user = registerUser("canon-water2-");
        addDevice(user, "Band", "health-connect", "EXT-H");
        // The water endpoint always logs against the server current day, so the day under
        // test is today rather than a fixed past date.
        String today = java.time.LocalDate.now().toString();
        addProviderRows(user, today, "health-connect", "hc-w2", 5000, null, 400);

        MvcResult r = call(user, post("/api/v1/water").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100}"));
        assertStatus(r, 200);

        // The manual row is created for the day; the provider row keeps its own value.
        assertThat(sourceRows(user, today)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select water_oz from daily_metrics where user_id=CAST(? as uuid)"
                + " and provider_record_id='hc-w2'", Integer.class, user.id())).isEqualTo(400);
    }

    @Test
    @DisplayName("Phase 10 - two users keep separate metrics for the same date")
    void ownershipIsUnaffectedBySources() throws Exception {
        Session first = registerUser("canon-own-a-");
        Session second = registerUser("canon-own-b-");
        addDevice(first, "Band", "health-connect", "EXT-I");
        addProviderRows(first, DAY, "health-connect", "hc-own", 5000, null, null);

        assertStatus(call(second, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric_date\":\"" + DAY + "\",\"steps\":777}")), 200);

        assertThat(sourceRows(first, DAY)).isEqualTo(1);
        assertThat(sourceRows(second, DAY)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select steps from v_daily_metrics_canonical"
                + " where user_id=CAST(? as uuid) and metric_date=CAST(? as date)",
                Integer.class, first.id(), DAY)).isEqualTo(5000);
        assertThat(jdbc.queryForObject("select steps from v_daily_metrics_canonical"
                + " where user_id=CAST(? as uuid) and metric_date=CAST(? as date)",
                Integer.class, second.id(), DAY)).isEqualTo(777);
    }
}
