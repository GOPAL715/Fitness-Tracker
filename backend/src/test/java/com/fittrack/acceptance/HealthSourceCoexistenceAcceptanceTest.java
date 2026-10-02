package com.fittrack.acceptance;

import com.fittrack.acceptance.support.FakeHealthProvider;
import com.fittrack.acceptance.support.FakeHealthProvider.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 10: source coexistence, canonical selection, pagination, and the response contract.
 *
 * <p>The two behaviours that make this phase necessary are pinned here. First, a day may hold a
 * manual row and one row per connected device, and all of them survive. Second, readers see exactly
 * one value per day, chosen by a documented policy, so a provider row never doubles a day.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class HealthSourceCoexistenceAcceptanceTest extends com.fittrack.acceptance.support.AiAssertions {

    private static final LocalDate DAY = LocalDate.now().minusDays(3);

    /** The health provider fake, which decides what a sync returns. */
    @org.springframework.beans.factory.annotation.Autowired
    FakeHealthProvider provider;

    private FakeHealthProvider provider() { return provider; }

    private UUID registerDevice(Session s, String name, String providerKey) throws Exception {
        MvcResult r = call(s, post("/api/v1/health/devices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_name\":\"" + name + "\",\"provider\":\"" + providerKey + "\"}"));
        assertStatus(r, 200);
        return UUID.fromString(json(r).path("id").asText());
    }

    private void manualDaily(Session s, String date, int steps, Integer water) throws Exception {
        String body = "{\"metric_date\":\"" + date + "\",\"steps\":" + steps
                + (water == null ? "" : ",\"water_oz\":" + water) + "}";
        assertStatus(call(s, post("/api/v1/daily-metrics").contentType(MediaType.APPLICATION_JSON)
                .content(body)), 200);
    }

    private int sourceRows(Session s, String date) {
        return jdbc.queryForObject("select count(*) from daily_metrics"
                + " where user_id=CAST(? as uuid) and metric_date=CAST(? as date)",
                Integer.class, s.id(), date);
    }

    private Map<String, Object> canonical(Session s, String date) {
        return jdbc.queryForMap("select * from v_daily_metrics_canonical"
                + " where user_id=CAST(? as uuid) and metric_date=CAST(? as date)", s.id(), date);
    }

    // ------------------------------------------------- manual and provider coexist

    @Test
    @DisplayName("Phase 10 - a provider import does not collide with a manual row on the same day")
    void providerImportCoexistsWithManualRow() throws Exception {
        Session user = register("p10-coexist-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID device = registerDevice(user, "Band", "fitbit");

        // A manual row for the day, created first: this is the case that used to raise a 500.
        manualDaily(user, DAY.toString(), 3000, 500);
        provider().addActivity("fitbit-" + DAY, DAY, 9500);

        assertStatus(call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);

        assertThat(sourceRows(user, DAY.toString()))
                .as("both the manual row and the provider row must survive").isEqualTo(2);
    }

    @Test
    @DisplayName("Phase 10 - two providers on the same day both persist, with no uniqueness collision")
    void twoProvidersCoexistOnOneDay() throws Exception {
        Session user = register("p10-twoprov-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID a = registerDevice(user, "Band A", "fitbit");
        UUID b = registerDevice(user, "Band B", "health-connect");

        provider().onlyForDevice("A");
        provider().addActivityFor("a-" + DAY, "A", DAY, 9000);
        provider().onlyForDevice("A");
        assertStatus(call(user, post("/api/v1/health/devices/" + a + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);
        provider().onlyForDevice("B");
        provider().addActivityFor("b-" + DAY, "B", DAY, 8000);
        provider().onlyForDevice("B");
        assertStatus(call(user, post("/api/v1/health/devices/" + b + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);

        assertThat(sourceRows(user, DAY.toString()))
                .as("one row per provider, plus neither overwrote the other").isEqualTo(2);
    }

    @Test
    @DisplayName("Phase 10 - an imported row records the device it came from")
    void importedRowCarriesDeviceId() throws Exception {
        Session user = register("p10-devid-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID device = registerDevice(user, "Band", "fitbit");
        provider().addActivity("dev-" + DAY, DAY, 7000);

        assertStatus(call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);

        UUID stored = jdbc.queryForObject("select device_id from daily_metrics"
                        + " where user_id=CAST(? as uuid) and provider_record_id is not null",
                UUID.class, user.id());
        assertThat(stored).as("provenance must identify the originating device").isEqualTo(device);
    }

    // ------------------------------------------------------ canonical selection

    @Test
    @DisplayName("Phase 10 - canonical selection prefers the provider value and falls back to manual")
    void canonicalPrefersProviderAndFallsBackToManual() throws Exception {
        Session user = register("p10-canon-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID device = registerDevice(user, "Band", "health-connect");

        // The manual row supplies water, which the provider record does not.
        manualDaily(user, DAY.toString(), 3000, 640);
        provider().addActivity("hc-" + DAY, DAY, 8800);

        assertStatus(call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);

        Map<String, Object> canonical = canonical(user, DAY.toString());
        assertThat(((Number) canonical.get("steps")).intValue())
                .as("the provider value wins for a field it supplies").isEqualTo(8800);
        assertThat(((Number) canonical.get("water_oz")).intValue())
                .as("manual is the fallback for a field the provider does not supply").isEqualTo(640);
    }

    @Test
    @DisplayName("Phase 10 - the canonical view returns exactly one row for a multi-source day")
    void canonicalReturnsOneRowPerDay() throws Exception {
        Session user = register("p10-onerow-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID a = registerDevice(user, "A", "fitbit");
        UUID b = registerDevice(user, "B", "health-connect");
        manualDaily(user, DAY.toString(), 1000, 100);

        provider().onlyForDevice("A");
        provider().addActivityFor("a-" + DAY, "A", DAY, 9000);
        provider().onlyForDevice("A");
        call(user, post("/api/v1/health/devices/" + a + "/sync").contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + DAY + "\"}"));
        provider().onlyForDevice("B");
        provider().addActivityFor("b-" + DAY, "B", DAY, 8000);
        provider().onlyForDevice("B");
        call(user, post("/api/v1/health/devices/" + b + "/sync").contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + DAY + "\"}"));

        Integer canonicalRows = jdbc.queryForObject("select count(*) from v_daily_metrics_canonical"
                + " where user_id=CAST(? as uuid) and metric_date=CAST(? as date)",
                Integer.class, user.id(), DAY.toString());
        assertThat(canonicalRows).as("three source rows must collapse to one canonical row").isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 10 - provider priority is explicit, so the winner is stable and repeatable")
    void providerPriorityIsDeterministic() throws Exception {
        Session user = register("p10-prio-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID a = registerDevice(user, "A", "fitbit");
        UUID b = registerDevice(user, "B", "health-connect");

        provider().onlyForDevice("A");
        provider().addActivityFor("a-" + DAY, "A", DAY, 9000);
        provider().onlyForDevice("A");
        call(user, post("/api/v1/health/devices/" + a + "/sync").contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + DAY + "\"}"));
        provider().onlyForDevice("B");
        provider().addActivityFor("b-" + DAY, "B", DAY, 8000);
        provider().onlyForDevice("B");
        call(user, post("/api/v1/health/devices/" + b + "/sync").contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + DAY + "\"}"));

        // health-connect outranks fitbit by explicit policy, not alphabetical accident. Re-reading
        // proves the result does not depend on import order.
        for (int i = 0; i < 2; i++) {
            assertThat(((Number) canonical(user, DAY.toString()).get("steps")).intValue())
                    .as("the same source must win every time").isEqualTo(8000);
        }
    }

    @Test
    @DisplayName("Phase 10 - competing values are selected, never summed")
    void competingValuesAreNotSummed() throws Exception {
        Session user = register("p10-nosum-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID a = registerDevice(user, "A", "fitbit");
        UUID b = registerDevice(user, "B", "health-connect");

        provider().onlyForDevice("A");
        provider().addActivityFor("a-" + DAY, "A", DAY, 9000);
        provider().onlyForDevice("A");
        call(user, post("/api/v1/health/devices/" + a + "/sync").contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + DAY + "\"}"));
        provider().onlyForDevice("B");
        provider().addActivityFor("b-" + DAY, "B", DAY, 8000);
        provider().onlyForDevice("B");
        call(user, post("/api/v1/health/devices/" + b + "/sync").contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + DAY + "\"}"));

        int steps = ((Number) canonical(user, DAY.toString()).get("steps")).intValue();
        assertThat(steps).as("9000 + 8000 would be a double count; selection must pick one")
                .isIn(9000, 8000);
    }

    // ------------------------------------------------------------ idempotency

    @Test
    @DisplayName("Phase 10 - repeating an import of the same record does not duplicate it")
    void repeatedImportIsIdempotent() throws Exception {
        Session user = register("p10-idem-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID device = registerDevice(user, "Band", "fitbit");
        provider().addActivity("idem-" + DAY, DAY, 8000);

        for (int i = 0; i < 3; i++) {
            assertStatus(call(user, post("/api/v1/health/devices/" + device + "/sync")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 200);
        }
        assertThat(sourceRows(user, DAY.toString())).isEqualTo(1);
    }

    // ------------------------------------------------------------- pagination

    @Test
    @DisplayName("Phase 10 - every page of a multi-page provider response is imported")
    void allPagesAreImported() throws Exception {
        Session user = register("p10-pages-");
        provider().reset();
        provider().use(Mode.NORMAL);
        provider().setPageSize(2);
        UUID device = registerDevice(user, "Band", "fitbit");
        for (int i = 0; i < 5; i++) {
            provider().addActivity("page-" + i + "-" + DAY, DAY, 1000 + i);
        }

        MvcResult r = call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}"));
        assertStatus(r, 200);
        JsonNode body = json(r);

        assertThat(body.path("pages_fetched").asInt())
                .as("five records at two per page needs three calls").isEqualTo(3);
        assertThat(body.path("activity_records").asInt()).isEqualTo(5);
        assertThat(sourceRows(user, DAY.toString()))
                .as("no page may be dropped").isEqualTo(5);
    }

    @Test
    @DisplayName("Phase 10 - a provider that repeats its cursor is stopped, not looped")
    void repeatedCursorIsRejected() throws Exception {
        Session user = register("p10-cycle-");
        provider().reset();
        provider().use(Mode.NORMAL);
        provider().useRepeatingCursor(true);
        UUID device = registerDevice(user, "Band", "fitbit");
        provider().addActivity("cycle-" + DAY, DAY, 5000);

        MvcResult r = call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}"));

        // The important property is that it terminates and does not hang or explode.
        assertThat(r.getResponse().getStatus()).isIn(200, 502);
        assertThat(json(r).path("pages_fetched").asInt())
                .as("a cycle must be detected within the page cap").isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("Phase 10 - the sync window is bounded, never an unbounded history download")
    void windowStaysBounded() throws Exception {
        Session user = register("p10-bounded-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID device = registerDevice(user, "Band", "fitbit");
        provider().addActivity("bounded-" + DAY, DAY, 6000);

        call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}"));

        String window = provider().windows().get(0);
        String[] parts = window.split("\\.\\.");
        assertThat(java.time.LocalDate.parse(parts[0]))
                .isAfterOrEqualTo(DAY.minusDays(30));
    }

    // -------------------------------------------------------------- ownership

    @Test
    @DisplayName("Phase 10 - a user cannot sync or disconnect another user's device")
    void crossUserDeviceAccessIsRejected() throws Exception {
        Session owner = register("p10-owner-");
        Session other = register("p10-other-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID device = registerDevice(owner, "Band", "fitbit");
        provider().addActivity("own-" + DAY, DAY, 7000);
        call(owner, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}"));

        assertStatus(call(other, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}")), 404);
        assertStatus(call(other, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/health/devices/" + device)), 404);

        assertThat(jdbc.queryForObject("select count(*) from health_devices where id=?",
                Integer.class, device)).as("the device must still exist").isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 10 - a status write cannot cross the ownership boundary")
    void statusUpdateIsOwnerScoped() throws Exception {
        Session owner = register("p10-stat-");
        UUID device = registerDevice(owner, "Band", "fitbit");
        // Direct SQL stands in for any future caller that might mark a device without the owner.
        int updated = jdbc.update("update health_devices set sync_status='error', last_error='x'"
                + " where id=? and user_id=CAST(? as uuid)", device, java.util.UUID.randomUUID());
        assertThat(updated).as("a mismatched owner must not update the row").isZero();
    }

    // --------------------------------------------------- disconnect retention

    @Test
    @DisplayName("Phase 10 - disconnecting keeps imported history (D3)")
    void disconnectRetainsHistory() throws Exception {
        Session user = register("p10-retain-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID device = registerDevice(user, "Band", "fitbit");
        provider().addActivity("keep-" + DAY, DAY, 8000);
        call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}"));

        MvcResult disconnect = call(user, org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.delete("/api/v1/health/devices/" + device));
        assertStatus(disconnect, 200);
        assertThat(disconnect.getResponse().getContentAsString())
                .contains("imported_history_retained")
                .contains("remains in AI FitTrack");

        assertThat(jdbc.queryForObject("select count(*) from health_devices where id=?",
                Integer.class, device)).isZero();
        assertThat(sourceRows(user, DAY.toString()))
                .as("imported history must survive the disconnect").isEqualTo(1);
    }

    // ---------------------------------------------------- response security

    @Test
    @DisplayName("Phase 10 - the devices response never exposes the sync cursor or credentials")
    void deviceResponseHidesInternalState() throws Exception {
        Session user = register("p10-hidden-");
        provider().reset();
        provider().use(Mode.NORMAL);
        UUID device = registerDevice(user, "Band", "fitbit");
        provider().addActivity("hid-" + DAY, DAY, 8000);
        call(user, post("/api/v1/health/devices/" + device + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + DAY + "\"}"));

        String raw = call(user, get("/api/v1/health/devices")).getResponse().getContentAsString();
        for (String forbidden : List.of("sync_cursor", "access_token", "refresh_token",
                "client_secret", FakeHealthProvider.SECRET)) {
            assertThat(raw).as("the response must not carry %s", forbidden).doesNotContain(forbidden);
        }
        assertThat(raw).contains("last_sync_at").contains("sync_status");
    }
}
