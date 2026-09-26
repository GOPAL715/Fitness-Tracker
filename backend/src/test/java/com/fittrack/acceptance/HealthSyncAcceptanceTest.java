package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fittrack.acceptance.support.FakeHealthProvider;
import com.fittrack.acceptance.support.FakeHealthProvider.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 15 B: health provider synchronization.
 *
 * <p>All provider interaction goes through a deterministic fake; no wearable credentials exist.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class HealthSyncAcceptanceTest extends AbstractAcceptanceTest {

    @TestConfiguration
    static class HealthConfig {
        @Bean @Primary
        FakeHealthProvider fakeHealthProvider() { return new FakeHealthProvider(); }
    }

    @Autowired FakeHealthProvider provider;

    @BeforeEach
    void resetProvider() { provider.reset(); }

    private static final LocalDate SYNC_DAY = LocalDate.of(2026, 6, 15);

    private UUID registerDevice(Session user, String externalId) throws Exception {
        MvcResult result = call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of(
                        "device_name", "Test Band", "device_type", "wearable",
                        "provider", "fake-wearable", "external_device_id", externalId))));
        assertThat(result.getResponse().getStatus())
                .as("register body %s", result.getResponse().getContentAsString()).isIn(200, 201);
        return UUID.fromString(json(result).path("id").asText());
    }

    private MvcResult sync(Session user, UUID deviceId, String to) throws Exception {
        return call(user, post("/api/v1/health/devices/" + deviceId + "/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + to + "\"}"));
    }

    private int importedActivityRows(String userId) {
        return jdbc.queryForObject("select count(*) from daily_metrics"
                + " where user_id=CAST(? as uuid) and provider_record_id is not null", Integer.class, userId);
    }

    // --------------------------------------------------- register and ownership

    @Test
    @DisplayName("Phase 15 health - a device is registered against the authenticated user")
    void deviceRegistrationIsOwnerScoped() throws Exception {
        Session user = register("health-reg-");
        UUID id = registerDevice(user, "band-1");

        Map<String, Object> row = jdbc.queryForMap(
                "select user_id,provider,external_device_id,sync_status from health_devices where id=?", id);
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("provider")).isEqualTo("fake-wearable");
        assertThat(row.get("external_device_id")).isEqualTo("band-1");

        MvcResult list = getAs(user, "/api/v1/health/devices");
        assertStatus(list, 200);
        assertThat(json(list).toString()).contains(id.toString());
    }

    @Test
    @DisplayName("Phase 15 health - a user cannot sync or disconnect another user's device")
    void crossUserDeviceAccessIsRejected() throws Exception {
        Session owner = register("health-owner-");
        Session other = register("health-other-");
        UUID id = registerDevice(owner, "band-owner");

        MvcResult foreignList = getAs(other, "/api/v1/health/devices");
        assertStatus(foreignList, 200);
        assertThat(foreignList.getResponse().getContentAsString()).doesNotContain(id.toString());

        assertStatus(sync(other, id, SYNC_DAY.toString()), 404);
        assertStatus(call(other, delete("/api/v1/health/devices/" + id)), 404);
        // The owner's connection survives.
        assertThat(jdbc.queryForObject("select count(*) from health_devices where id=?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 15 health - credentials are never accepted or stored")
    void providerCredentialsAreRejected() throws Exception {
        Session user = register("health-creds-");
        MvcResult result = call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_name\":\"Band\",\"access_token\":\"sk-live-REAL-TOKEN\"}"));
        assertStatus(result, 400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("REAL-TOKEN");
    }

    // ------------------------------------------- items 14/15 sync idempotency

    @Test
    @DisplayName("Phase 15 health - a successful sync imports provider records as the caller")
    void successfulSyncImportsRecords() throws Exception {
        Session user = register("health-sync-");
        UUID id = registerDevice(user, "band-sync");
        provider.addActivity("rec-a1", SYNC_DAY, 8000);
        provider.addBody("rec-b1", SYNC_DAY, "180.5");

        MvcResult result = sync(user, id, SYNC_DAY.toString());
        assertStatus(result, 200);
        JsonNode body = json(result);
        assertThat(body.path("status").asText()).isEqualTo("synced");
        assertThat(body.path("activity_records").asInt()).isEqualTo(1);
        assertThat(body.path("body_records").asInt()).isEqualTo(1);

        assertThat(importedActivityRows(user.id())).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "select user_id,source,provider_record_id,steps from daily_metrics where user_id=CAST(? as uuid)",
                user.id());
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("source")).isEqualTo("fake-wearable");
        assertThat(row.get("provider_record_id")).isEqualTo("rec-a1");
        assertThat(row.get("steps")).isEqualTo(8000);
        assertThat(jdbc.queryForObject("select sync_status from health_devices where id=?", String.class, id))
                .isEqualTo("synced");
    }

    @Test
    @DisplayName("Phase 15 health - repeating a sync does not duplicate imported rows")
    void repeatedSyncIsIdempotent() throws Exception {
        Session user = register("health-idem-");
        UUID id = registerDevice(user, "band-idem");
        provider.addActivity("rec-a1", SYNC_DAY, 8000);
        provider.addBody("rec-b1", SYNC_DAY, "180.5");

        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);
        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);
        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);

        assertThat(importedActivityRows(user.id()))
                .as("three syncs of the same provider record produce one row").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from body_metrics where user_id=CAST(? as uuid)"
                + " and provider_record_id is not null", Integer.class, user.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 15 health - an updated provider record updates in place rather than duplicating")
    void changedProviderRecordUpdatesInPlace() throws Exception {
        Session user = register("health-update-");
        UUID id = registerDevice(user, "band-update");
        provider.addActivity("rec-a1", SYNC_DAY, 8000);
        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);

        // Same record id, corrected value.
        provider.addActivity("rec-a1", SYNC_DAY, 9500);
        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);

        assertThat(importedActivityRows(user.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select steps from daily_metrics where user_id=CAST(? as uuid)"
                + " and provider_record_id='rec-a1'", Integer.class, user.id())).isEqualTo(9500);
    }

    @Test
    @DisplayName("Phase 15 health - the sync window is bounded and incremental on a second pass")
    void syncIsBoundedAndIncremental() throws Exception {
        Session user = register("health-window-");
        UUID id = registerDevice(user, "band-window");

        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);
        assertThat(provider.windows()).hasSize(1);
        // The first pass uses the configured bounded window, never an unbounded history pull.
        assertThat(provider.windows().get(0))
                .as("bounded window ending on the requested day")
                .isEqualTo(SYNC_DAY.minusDays(29) + ".." + SYNC_DAY);

        // The cursor is now set, so the next pass resumes rather than re-reading the window.
        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);
        assertThat(provider.windows().get(1))
                .as("second pass resumes from the stored watermark")
                .isEqualTo(SYNC_DAY + ".." + SYNC_DAY);
    }

    // ------------------------------------------------ item 16 provider failures

    @Test
    @DisplayName("Phase 15 health - a provider timeout fails safely with a stable category")
    void providerTimeoutIsReportedAsUnavailable() throws Exception {
        Session user = register("health-timeout-");
        UUID id = registerDevice(user, "band-timeout");
        provider.addActivity("rec-a1", SYNC_DAY, 8000);
        provider.use(Mode.TIMEOUT);

        MvcResult result = sync(user, id, SYNC_DAY.toString());
        assertStatus(result, 502);
        assertThat(result.getResponse().getContentAsString())
                .contains("timeout").doesNotContain(FakeHealthProvider.SECRET);
        assertThat(importedActivityRows(user.id())).as("a failed sync imports nothing").isZero();
        assertThat(jdbc.queryForObject("select sync_status from health_devices where id=?", String.class, id))
                .isEqualTo("error");
    }

    @Test
    @DisplayName("Phase 15 health - a provider 5xx is reported without provider detail")
    void providerServerErrorIsReportedSafely() throws Exception {
        Session user = register("health-5xx-");
        UUID id = registerDevice(user, "band-5xx");
        provider.use(Mode.SERVER_ERROR);

        MvcResult result = sync(user, id, SYNC_DAY.toString());
        assertStatus(result, 502);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(FakeHealthProvider.SECRET).doesNotContain("503");
        assertThat(jdbc.queryForObject("select last_error from health_devices where id=?", String.class, id))
                .isEqualTo("unavailable");
    }

    @Test
    @DisplayName("Phase 15 health - an authentication failure is reported as 401")
    void providerAuthFailureIsReported() throws Exception {
        Session user = register("health-auth-");
        UUID id = registerDevice(user, "band-auth");
        provider.use(Mode.AUTH_FAILURE);

        MvcResult result = sync(user, id, SYNC_DAY.toString());
        assertStatus(result, 401);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(FakeHealthProvider.SECRET);
    }

    @Test
    @DisplayName("Phase 15 health - malformed provider data is rejected, not imported")
    void malformedProviderDataIsRejected() throws Exception {
        Session user = register("health-malformed-");
        UUID id = registerDevice(user, "band-malformed");
        provider.use(Mode.MALFORMED);

        MvcResult result = sync(user, id, SYNC_DAY.toString());
        assertStatus(result, 502);
        assertThat(importedActivityRows(user.id())).isZero();
        assertThat(jdbc.queryForObject("select last_error from health_devices where id=?", String.class, id))
                .isEqualTo("malformed");
    }

    @Test
    @DisplayName("Phase 15 health - a partial response imports only what arrived, without corruption")
    void partialProviderResponseImportsOnlyDeliveredRecords() throws Exception {
        Session user = register("health-partial-");
        UUID id = registerDevice(user, "band-partial");
        provider.addActivity("rec-a1", SYNC_DAY, 8000);
        provider.addActivity("rec-a2", SYNC_DAY.plusDays(1), 9000);
        provider.addBody("rec-b1", SYNC_DAY, "180.5");
        provider.use(Mode.PARTIAL);

        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);
        assertThat(importedActivityRows(user.id()))
                .as("only the delivered record is stored").isEqualTo(1);
        // The rest can be picked up by a later incremental pass.
        provider.use(Mode.NORMAL);
        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);
        assertThat(importedActivityRows(user.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("Phase 15 health - a throwing provider leaves the device in an error state, not a broken row")
    void throwingProviderIsContained() throws Exception {
        Session user = register("health-throw-");
        UUID id = registerDevice(user, "band-throw");
        provider.use(Mode.THROWING);

        MvcResult result = sync(user, id, SYNC_DAY.toString());
        assertThat(result.getResponse().getStatus())
                .as("an unexpected provider fault is a 5xx, never a partial write").isEqualTo(500);
        assertThat(importedActivityRows(user.id())).isZero();
    }

    // ------------------------------------------------ disconnect and ownership

    @Test
    @DisplayName("Phase 15 health - disconnecting removes only the caller's own connection")
    void disconnectRemovesTheCallersDevice() throws Exception {
        Session user = register("health-disconnect-");
        UUID id = registerDevice(user, "band-bye");

        MvcResult result = call(user, delete("/api/v1/health/devices/" + id));
        assertStatus(result, 200);
        assertThat(json(result).path("disconnected").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from health_devices where id=?", Integer.class, id))
                .isZero();
        // A second disconnect is a clean 404, not an error.
        assertStatus(call(user, delete("/api/v1/health/devices/" + id)), 404);
    }

    @Test
    @DisplayName("Phase 15 health - imported records stay owned by the importer")
    void importedRecordsAreOwnedByTheImporter() throws Exception {
        Session user = register("health-owned-");
        Session other = register("health-notmine-");
        UUID id = registerDevice(user, "band-owned");
        provider.addActivity("rec-a1", SYNC_DAY, 7777);
        assertStatus(sync(user, id, SYNC_DAY.toString()), 200);

        // The importer can read it through the analytics surface.
        MvcResult mine = getAs(user, "/api/v1/analytics/activity?from=" + SYNC_DAY + "&to=" + SYNC_DAY);
        assertStatus(mine, 200);
        assertThat(json(mine).get(0).path("steps").asInt()).isEqualTo(7777);

        // Another user never sees it, even though the provider record id is a known constant.
        MvcResult theirs = getAs(other, "/api/v1/analytics/activity?from=" + SYNC_DAY + "&to=" + SYNC_DAY);
        assertStatus(theirs, 200);
        assertThat(theirs.getResponse().getContentAsString()).doesNotContain("7777");
        assertThat(jdbc.queryForObject("select count(*) from daily_metrics"
                + " where user_id=CAST(? as uuid) and provider_record_id is not null", Integer.class, other.id()))
                .isZero();
    }
}
