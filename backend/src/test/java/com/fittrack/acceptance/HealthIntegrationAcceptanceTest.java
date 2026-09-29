package com.fittrack.acceptance;

import com.fittrack.acceptance.support.FakeHealthProvider;
import com.fittrack.health.HealthConnectionState;
import com.fittrack.health.HealthProviderCatalog;
import com.fittrack.health.HealthProviders;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 20: the health integration surface.
 *
 * <p>Three properties are pinned, and they are the ones a user or an operator would otherwise have
 * to take on trust. The catalogue is served by the server rather than hardcoded in a client, so what
 * a browser renders and what the backend will accept cannot drift. A connection reports one of five
 * states derived from what is actually stored. And the aggregate app-data route, which reads the
 * same table, no longer publishes the two internal columns it used to leak.
 *
 * <p>All provider interaction goes through the deterministic fake; no wearable credentials exist and
 * no external service is contacted.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class HealthIntegrationAcceptanceTest extends com.fittrack.acceptance.support.AiAssertions {

    private MvcResult integrations(Session user) throws Exception {
        return getAs(user, "/api/v1/health/integrations");
    }

    private JsonNode integrationFor(Session user, String provider) throws Exception {
        MvcResult result = integrations(user);
        assertStatus(result, 200);
        for (JsonNode node : json(result).path("integrations")) {
            if (provider.equals(node.path("provider").asText())) {
                return node;
            }
        }
        throw new AssertionError("no integration returned for provider " + provider);
    }

    private UUID connect(Session user, String provider, String name) throws Exception {
        MvcResult result = call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of(
                        "device_name", name, "device_type", "wearable", "provider", provider))));
        assertThat(result.getResponse().getStatus())
                .as("connect body %s", result.getResponse().getContentAsString()).isIn(200, 201);
        return UUID.fromString(json(result).path("id").asText());
    }

    /** Forces a stored sync status so each state can be produced without a real provider call. */
    private void setSyncStatus(UUID deviceId, String status) {
        jdbc.update("update health_devices set sync_status=? where id=?", status, deviceId);
    }

    @Test
    @DisplayName("Phase 20 - the catalogue is served by the server and matches the backend allowlist")
    void catalogueIsServerAuthoritative() throws Exception {
        Session user = register("health-cat-");
        List<String> served = new ArrayList<>();
        for (JsonNode node : json(integrations(user)).path("integrations")) {
            served.add(node.path("provider").asText());
        }
        // Every provider the backend would accept must be described, or a client cannot discover it.
        assertThat(served).containsExactlyInAnyOrderElementsOf(HealthProviderCatalog.clientVisible()
                .stream().map(HealthProviderCatalog.ProviderDescriptor::key).toList());
        // And every key the server accepts is one the server describes: the drift the phase exists to
        // prevent, asserted at the HTTP boundary rather than only in a unit test.
        assertThat(HealthProviderCatalog.disagreementWith(HealthProviders.SUPPORTED)).isEmpty();
    }

    @Test
    @DisplayName("Phase 20 - the in-process test provider is never served to a client")
    void testProviderIsNeverServed() throws Exception {
        Session user = register("health-cat-hidden-");
        // The key is accepted on the write route, so the test double can attribute its rows, but a
        // browser must never be offered an integration that reaches no external service.
        connect(user, "fake-wearable", "In-process double");
        assertThat(integrations(user).getResponse().getContentAsString())
                .as("the test provider reaches no external service and must not be offered")
                .doesNotContain("fake-wearable");
    }

    @Test
    @DisplayName("Phase 20 - the catalogue states honestly that no credential is held")
    void catalogueNeverClaimsACredential() throws Exception {
        Session user = register("health-cat-cred-");
        for (JsonNode node : json(integrations(user)).path("integrations")) {
            assertThat(node.path("credential_model").asText())
                    .as("credential model for %s", node.path("provider").asText())
                    .isEqualTo("none");
            assertThat(node.path("boundary").asText())
                    .as("boundary for %s", node.path("provider").asText())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Phase 20 - a provider with no connection reports disconnected")
    void noConnectionIsDisconnected() throws Exception {
        Session user = register("health-state-none-");
        assertThat(integrationFor(user, "fitbit").path("connection_state").asText())
                .isEqualTo(HealthConnectionState.DISCONNECTED);
        assertThat(integrationFor(user, "fitbit").path("connections"))
                .as("a provider with no connection has none").isEmpty();
    }

    @Test
    @DisplayName("Phase 20 - each stored sync state is reported as the matching connection state")
    void everyStateIsReported() throws Exception {
        Session user = register("health-state-all-");
        UUID device = connect(user, "health-connect", "State probe");

        setSyncStatus(device, "idle");
        assertThat(integrationFor(user, "health-connect").path("connection_state").asText())
                .as("registered but never synced is connecting")
                .isEqualTo(HealthConnectionState.CONNECTING);

        setSyncStatus(device, "syncing");
        assertThat(integrationFor(user, "health-connect").path("connection_state").asText())
                .isEqualTo(HealthConnectionState.SYNCING);

        setSyncStatus(device, "synced");
        assertThat(integrationFor(user, "health-connect").path("connection_state").asText())
                .isEqualTo(HealthConnectionState.CONNECTED);

        setSyncStatus(device, "error");
        assertThat(integrationFor(user, "health-connect").path("connection_state").asText())
                .isEqualTo(HealthConnectionState.SYNC_FAILED);
    }

    @Test
    @DisplayName("Phase 20 - every state the client can be asked to render is one the server can produce")
    void everyAdvertisedStateIsProducible() throws Exception {
        // A state no stored value can produce is dead UI code, so the client contract and the server
        // contract are checked against each other rather than trusted.
        Session user = register("health-state-enum-");
        List<String> produced = new ArrayList<>();
        UUID device = connect(user, "health-connect", "Enum probe");
        for (String status : List.of("idle", "syncing", "synced", "error")) {
            setSyncStatus(device, status);
            produced.add(integrationFor(user, "health-connect").path("connection_state").asText());
        }
        produced.add(integrationFor(user, "garmin").path("connection_state").asText());
        assertThat(produced).containsExactlyInAnyOrderElementsOf(HealthConnectionState.allStates());
    }

    @Test
    @DisplayName("Phase 20 - disconnecting returns the provider to disconnected")
    void disconnectReturnsToDisconnected() throws Exception {
        Session user = register("health-state-disc-");
        UUID device = connect(user, "health-connect", "Bye probe");
        setSyncStatus(device, "synced");
        assertThat(integrationFor(user, "health-connect").path("connection_state").asText())
                .isEqualTo(HealthConnectionState.CONNECTED);

        assertStatus(call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/health/devices/" + device)), 200);

        JsonNode after = integrationFor(user, "health-connect");
        assertThat(after.path("connection_state").asText())
                .isEqualTo(HealthConnectionState.DISCONNECTED);
        assertThat(after.path("connections")).isEmpty();
    }

    @Test
    @DisplayName("Phase 20 - a failing connection outranks a healthy one on the same provider")
    void failureOutranksAHealthySibling() throws Exception {
        // One provider can own several rows. A single badge has to choose, and someone reading this
        // screen needs to know what is broken before what works.
        Session user = register("health-state-multi-");
        UUID healthy = connect(user, "health-connect", "Working band");
        UUID broken = connect(user, "health-connect", "Broken band");
        setSyncStatus(healthy, "synced");
        setSyncStatus(broken, "error");

        assertThat(integrationFor(user, "health-connect").path("connection_state").asText())
                .isEqualTo(HealthConnectionState.SYNC_FAILED);
        assertThat(integrationFor(user, "health-connect").path("connections"))
                .as("both connections are still listed, so the user can act on either")
                .hasSize(2);
    }

    @Test
    @DisplayName("Phase 20 - one user never sees another user's health connection")
    void integrationsAreOwnerScoped() throws Exception {
        Session owner = register("health-iso-owner-");
        Session stranger = register("health-iso-other-");
        UUID device = connect(owner, "health-connect", "Private band");
        setSyncStatus(device, "synced");

        // The stranger's catalogue is complete - it describes every provider - but carries none of the
        // owner's connections, and no trace of the device name.
        String raw = integrations(stranger).getResponse().getContentAsString();
        assertThat(raw).doesNotContain("Private band");
        assertThat(raw).doesNotContain(device.toString());
        assertThat(raw).doesNotContain(owner.id());
        assertThat(integrationFor(stranger, "health-connect").path("connections")).isEmpty();
        assertThat(integrationFor(stranger, "health-connect").path("connection_state").asText())
                .isEqualTo(HealthConnectionState.DISCONNECTED);

        // And the owner still sees their own, so the scoping did not simply hide everything.
        assertThat(integrationFor(owner, "health-connect").path("connections")).hasSize(1);
    }

    @Test
    @DisplayName("Phase 20 - the catalogue requires authentication")
    void catalogueRequiresAuthentication() throws Exception {
        // A description of what this deployment can integrate is still per-user data once it carries
        // that user's connections, so it is not public even though the liveness route next to it is.
        assertUnauthenticated(get("/api/v1/health/integrations"));
    }

    @Test
    @DisplayName("Phase 20 - app-data no longer publishes the sync cursor or the changes token")
    void appDataDoesNotLeakInternalDeviceState() throws Exception {
        Session user = register("health-leak-appdata-");
        UUID device = connect(user, "health-connect", "Cursor probe");
        // Give both internal columns a value a test can search for, so the assertion proves the
        // columns are not selected rather than merely happening to be null.
        jdbc.update("update health_devices set sync_cursor=?, client_changes_token=? where id=?",
                "2026-06-15", "CURSORTOKENMUSTNOTLEAK", device);

        String appData = call(user, get("/api/v1/app-data")).getResponse().getContentAsString();

        // sync_cursor is the server's own day watermark: it decides the window the next sync re-reads,
        // and docs/database.md already promises it is never returned by the API. The dedicated device
        // route excluded it and this aggregate route did not, so the exclusion was undone by a second
        // reader rather than by a decision.
        assertThat(appData)
                .as("the sync watermark must not reach the browser through any route")
                .doesNotContain("sync_cursor")
                .doesNotContain("2026-06-15");
        // client_changes_token is the Android client's own opaque resume handle. V11 keeps it strictly
        // separate from the server cursor; publishing it puts the client's state in a third party's hands.
        assertThat(appData)
                .as("the client's changes token must not reach the browser")
                .doesNotContain("client_changes_token")
                .doesNotContain("CURSORTOKENMUSTNOTLEAK");

        // The route still returns devices, and still returns the fields the UI actually renders, so
        // the fix closed the leak rather than the feature.
        assertThat(appData).contains("devices").contains("Cursor probe").contains("sync_status");
    }

    @Test
    @DisplayName("Phase 20 - no health route returns a credential or an internal cursor")
    void noHealthRouteLeaksInternals() throws Exception {
        Session user = register("health-leak-routes-");
        UUID device = connect(user, "health-connect", "Route probe");
        setSyncStatus(device, "synced");
        jdbc.update("update health_devices set sync_cursor=?, client_changes_token=? where id=?",
                "2026-01-01", "TOKENMUSTNOTLEAK", device);

        for (String path : List.of("/api/v1/health/integrations", "/api/v1/health/devices",
                "/api/v1/app-data")) {
            String body = call(user, get(path)).getResponse().getContentAsString();
            assertThat(body).as("GET %s", path)
                    .doesNotContain("sync_cursor")
                    .doesNotContain("client_changes_token")
                    .doesNotContain("TOKENMUSTNOTLEAK")
                    .doesNotContain("access_token")
                    .doesNotContain("refresh_token")
                    .doesNotContain("client_secret")
                    .doesNotContain(FakeHealthProvider.SECRET);
        }
    }

    @Test
    @DisplayName("Phase 20 - permission_status is now actually returned, and stays a reported claim")
    void permissionStatusIsReturnedButAuthorizesNothing() throws Exception {
        Session user = register("health-perm-");
        UUID device = connect(user, "health-connect", "Permission probe");
        setSyncStatus(device, "synced");
        // The V11 ingest path writes this column; Phase 20 only completes the read side, so the value
        // is set directly here rather than by replaying a Health Connect batch.
        jdbc.update("update health_devices set permission_status='permission_required' where id=?", device);

        // The field is reachable now. It was declared in the frontend types and rendered by the
        // profile screen, but no DTO carried it and no query selected it, so the badge could never
        // appear.
        assertThat(call(user, get("/api/v1/health/devices")).getResponse().getContentAsString())
                .contains("permission_status")
                .contains("permission_required");
        assertThat(integrationFor(user, "health-connect").path("connections").get(0)
                .path("permission_status").asText())
                .isEqualTo("permission_required");

        // And it remains a device-reported claim: a connection reporting a revoked permission is not
        // locked out, and the row is still readable by its owner.
        jdbc.update("update health_devices set permission_status='permission_revoked' where id=?", device);
        assertStatus(call(user, get("/api/v1/health/devices")), 200);
        assertThat(integrationFor(user, "health-connect").path("connection_state").asText())
                .as("a reported permission state must never deny the owner access to their own data")
                .isEqualTo(HealthConnectionState.CONNECTED);
    }

    @Test
    @DisplayName("Phase 20 - a connection with no reported permission carries null, not a guess")
    void absentPermissionIsNullNotGuessed() throws Exception {
        Session user = register("health-perm-null-");
        connect(user, "health-connect", "Silent probe");
        assertThat(integrationFor(user, "health-connect").path("connections").get(0)
                .path("permission_status").isNull())
                .as("a device that reported nothing must not be given an invented state")
                .isTrue();
    }
}
