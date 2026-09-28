package com.fittrack.acceptance;

import com.fittrack.health.HealthProviders;
import com.fittrack.acceptance.support.FakeHealthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 10 hardening: what a client is and is not allowed to declare about a connection.
 *
 * <p>The provider value on a connection is a <b>declared integration type</b>, not proof that the
 * user owns an external account. No integration performs an OAuth exchange yet, so there is nothing
 * to attest against. What the server can do - and does - is refuse a value it does not recognise,
 * so a typo or a fabricated string cannot create a row that no pipeline can ever attribute.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class HealthProviderAllowlistAcceptanceTest extends com.fittrack.acceptance.support.AiAssertions {

    private MvcResult register(Session s, String provider) throws Exception {
        return call(s, post("/api/v1/health/devices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_name\":\"Probe\",\"provider\":\"" + provider + "\"}"));
    }

    @Test
    @DisplayName("Phase 10 - every supported provider is accepted")
    void supportedProvidersAreAccepted() throws Exception {
        Session user = register("allow-ok-");
        for (String provider : HealthProviders.SUPPORTED) {
            MvcResult result = register(user, provider);
            assertStatus(result, 200);
            assertThat(json(result).path("provider").asText())
                    .as("provider %s must be stored in canonical form", provider)
                    .isEqualTo(provider);
        }
    }

    @Test
    @DisplayName("Phase 10 - a provider name is normalised rather than stored as typed")
    void providerNamesAreNormalised() throws Exception {
        Session user = register("allow-case-");
        MvcResult result = register(user, "FitBit");
        assertStatus(result, 200);
        assertThat(json(result).path("provider").asText())
                .as("case must not create a second, unmatchable provider value")
                .isEqualTo("fitbit");
    }

    @Test
    @DisplayName("Phase 10 - an arbitrary provider string is rejected, not stored")
    void arbitraryProviderIsRejected() throws Exception {
        Session user = register("allow-bad-");
        for (String bogus : List.of(
                "my-own-tracker",
                "../../etc/passwd",
                "fitbit; drop table app_users",
                "<script>",
                "FITBIT_V2")) {
            MvcResult result = register(user, bogus);
            assertStatus(result, 400);
            assertThat(result.getResponse().getContentAsString())
                    .as("the refusal must not echo an unsupported value back")
                    .doesNotContain("drop table");
        }

        assertThat(jdbc.queryForObject("select count(*) from health_devices where user_id=CAST(? as uuid)",
                Integer.class, user.id()))
                .as("no device may exist for a rejected provider").isZero();
    }

    @Test
    @DisplayName("Phase 10 - a missing provider falls back to a server-chosen value, not a client one")
    void missingProviderUsesServerDefault() throws Exception {
        Session user = register("allow-default-");
        MvcResult result = call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON).content("{\"device_name\":\"Probe\"}"));
        assertStatus(result, 200);
        assertThat(json(result).path("provider").asText())
                .as("the default is decided by the server")
                .isIn(HealthProviders.SUPPORTED);
    }

    @Test
    @DisplayName("Phase 10 - credentials are still refused on registration")
    void credentialsRemainRefused() throws Exception {
        Session user = register("allow-cred-");
        MvcResult result = call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_name\":\"Probe\",\"provider\":\"fitbit\","
                        + "\"access_token\":\"sk-live-SHOULD-BE-REJECTED\"}"));
        assertStatus(result, 400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SHOULD-BE-REJECTED");
    }

    @Test
    @DisplayName("Phase 10 - the device response exposes no internal or credential field")
    void deviceResponseIsAnAllowlist() throws Exception {
        Session user = register("allow-resp-");
        register(user, "health-connect");
        String raw = call(user, get("/api/v1/health/devices")).getResponse().getContentAsString();

        for (String forbidden : List.of("sync_cursor", "user_id", "access_token", "refresh_token",
                "client_secret", "provider_record_id", FakeHealthProvider.SECRET)) {
            // "device_id" is deliberately not asserted: it is a substring of external_device_id,
            // which the product needs. A bare substring check would be a false positive.
            assertThat(raw).as("the response must not carry %s", forbidden).doesNotContain(forbidden);
        }
        assertThat(raw).contains("sync_status").contains("last_sync_at").contains("awaiting_first_sync");
    }
}
