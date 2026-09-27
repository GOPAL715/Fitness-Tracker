package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 2: profile and onboarding acceptance tests.
 *
 * <p>These drive the exact payload {@code ProfileView} sends, because the profile save previously
 * failed for two reasons only a realistic payload would catch: the backend whitelist named
 * {@code sleep_target} while the column and the client both use {@code sleep_target_hours}, and no
 * profile row was ever created for a new account.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class ProfileAcceptanceTest extends AbstractAcceptanceTest {

    /** The complete body ProfileView PUTs, field for field. */
    private Map<String, Object> profileViewPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("display_name", "Sam Rivera");
        payload.put("goal", "Build strength");
        payload.put("fitness_level", "Advanced");
        payload.put("equipment", "Home gym");
        payload.put("limitations", "Lower back sensitivity");
        payload.put("activity_target", 5);
        payload.put("weekly_minutes", 240);
        payload.put("sleep_target_hours", 7.5);
        payload.put("step_target", 12000);
        payload.put("calorie_target", 2600);
        payload.put("protein_target_g", 180);
        payload.put("water_target_oz", 120);
        payload.put("target_weight_lb", 168.5);
        return payload;
    }

    private MvcResult putProfile(Session session, String id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/fitness-profile/" + id)
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andReturn();
    }

    private String profileId(Session session) throws Exception {
        JsonNode profile = json(getAs(session, "/api/v1/app-data")).path("profile");
        assertThat(profile.isMissingNode() || profile.isNull())
                .as("a registered account must have a profile row").isFalse();
        return profile.path("id").asText();
    }

    private int profilesFor(String userId) {
        return jdbc.queryForObject("select count(*) from fitness_profile where user_id = CAST(? as uuid)",
                Integer.class, userId);
    }

    /* ---------- Registration creates the profile ---------- */

    @Test
    @DisplayName("registration creates a default fitness_profile in the same transaction")
    void registrationCreatesStarterProfile() throws Exception {
        Session session = register("profile-register-");
        assertThat(profilesFor(session.id())).as("exactly one profile row per new account").isEqualTo(1);
    }

    @Test
    @DisplayName("the starter profile carries the onboarding defaults")
    void starterProfileHasOnboardingDefaults() throws Exception {
        Session session = register("profile-defaults-");
        JsonNode profile = json(getAs(session, "/api/v1/app-data")).path("profile");

        assertThat(profile.path("display_name").asText()).isEqualTo("Alex Morgan");
        assertThat(profile.path("goal").asText()).isEqualTo("Build strength");
        assertThat(profile.path("activity_target").asInt()).isEqualTo(4);
        assertThat(profile.path("weekly_minutes").asInt()).isEqualTo(180);
        // Not null, so the Profile form renders real values rather than its client-side fallbacks.
        assertThat(profile.path("sleep_target_hours").isNull()).isFalse();
        assertThat(profile.path("step_target").isNull()).isFalse();
    }

    @Test
    @DisplayName("two accounts never share a profile")
    void eachAccountGetsItsOwnProfile() throws Exception {
        Session a = register("profile-own-a-");
        Session b = register("profile-own-b-");

        assertThat(profileId(a)).isNotEqualTo(profileId(b));
        assertThat(profilesFor(a.id())).isEqualTo(1);
        assertThat(profilesFor(b.id())).isEqualTo(1);
    }
    /* ---------- The full ProfileView round trip ---------- */

    @Test
    @DisplayName("the exact ProfileView payload round-trips: PUT 200, then every field persisted")
    void profileViewPayloadRoundTrips() throws Exception {
        Session session = register("profile-roundtrip-");
        String id = profileId(session);

        assertStatus(putProfile(session, id, profileViewPayload()), 200);

        // Read back through the same endpoint the Profile screen uses.
        JsonNode profile = json(getAs(session, "/api/v1/fitness-profile/" + id));
        assertThat(profile.path("display_name").asText()).isEqualTo("Sam Rivera");
        assertThat(profile.path("goal").asText()).isEqualTo("Build strength");
        assertThat(profile.path("fitness_level").asText()).isEqualTo("Advanced");
        assertThat(profile.path("equipment").asText()).isEqualTo("Home gym");
        assertThat(profile.path("limitations").asText()).isEqualTo("Lower back sensitivity");
        assertThat(profile.path("activity_target").asInt()).isEqualTo(5);
        assertThat(profile.path("weekly_minutes").asInt()).isEqualTo(240);
        // The field whose name mismatch previously made every save fail with a 400.
        assertThat(profile.path("sleep_target_hours").asDouble()).isEqualTo(7.5);
        assertThat(profile.path("step_target").asInt()).isEqualTo(12000);
        assertThat(profile.path("calorie_target").asInt()).isEqualTo(2600);
        assertThat(profile.path("protein_target_g").asInt()).isEqualTo(180);
        assertThat(profile.path("water_target_oz").asInt()).isEqualTo(120);
        assertThat(profile.path("target_weight_lb").asDouble()).isEqualTo(168.5);
    }

    @Test
    @DisplayName("sleep_target_hours is accepted, so the sleep field is editable")
    void sleepTargetHoursIsWritable() throws Exception {
        Session session = register("profile-sleep-");
        String id = profileId(session);

        assertStatus(putProfile(session, id, Map.of("sleep_target_hours", 9.25)), 200);

        assertThat(jdbc.queryForObject("select sleep_target_hours from fitness_profile where id = CAST(? as uuid)",
                String.class, id)).isEqualTo("9.25");
    }

    @Test
    @DisplayName("a save is visible through app-data, which is what the app actually reads")
    void savedProfileIsVisibleThroughAppData() throws Exception {
        Session session = register("profile-appdata-");
        String id = profileId(session);

        assertStatus(putProfile(session, id, profileViewPayload()), 200);

        JsonNode profile = json(getAs(session, "/api/v1/app-data")).path("profile");
        assertThat(profile.path("id").asText()).isEqualTo(id);
        assertThat(profile.path("display_name").asText()).isEqualTo("Sam Rivera");
        assertThat(profile.path("sleep_target_hours").asDouble()).isEqualTo(7.5);
    }

    @Test
    @DisplayName("a partial update leaves untouched fields alone")
    void partialUpdateKeepsOtherFields() throws Exception {
        Session session = register("profile-partial-");
        String id = profileId(session);
        assertStatus(putProfile(session, id, profileViewPayload()), 200);

        assertStatus(putProfile(session, id, Map.of("goal", "Lose fat")), 200);

        JsonNode profile = json(getAs(session, "/api/v1/fitness-profile/" + id));
        assertThat(profile.path("goal").asText()).isEqualTo("Lose fat");
        assertThat(profile.path("display_name").asText()).as("untouched field survives").isEqualTo("Sam Rivera");
    }
    @Test
    @DisplayName("creating a second profile is a 400, not a unique-constraint 500")
    void secondProfileIsRejectedAsAClientError() throws Exception {
        Session session = register("profile-dup-");

        // The profile is a per-account singleton created at registration.
        MvcResult second = mvc.perform(post("/api/v1/fitness-profile")
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"display_name\":\"Impostor\"}")).andReturn();

        assertStructuredError(second, 400, "Bad Request");
        assertThat(profilesFor(session.id())).as("still exactly one profile").isEqualTo(1);
    }

    /* ---------- Ownership ---------- */

    @Test
    @DisplayName("user B cannot read, update or delete user A's profile")
    void anotherUserCannotReachTheProfile() throws Exception {
        Session a = register("profile-a-");
        Session b = register("profile-b-");
        String idA = profileId(a);
        String path = "/api/v1/fitness-profile/" + idA;

        // 404 rather than 403, so the endpoint never confirms that the row exists.
        assertStatus(getAs(b, path), 404);
        assertStatus(putProfile(b, idA, Map.of("display_name", "Stolen")), 404);
        assertStatus(mvc.perform(delete(path).header("Authorization", "Bearer " + b.access())).andReturn(), 404);

        // A's profile is untouched by the attempt.
        assertThat(json(getAs(a, path)).path("display_name").asText()).isEqualTo("Alex Morgan");
    }

    @Test
    @DisplayName("a forged user_id in the body is rejected outright")
    void clientSuppliedUserIdIsRejected() throws Exception {
        Session a = register("profile-forge-a-");
        Session b = register("profile-forge-b-");
        String idA = profileId(a);

        Map<String, Object> forged = new LinkedHashMap<>(profileViewPayload());
        forged.put("user_id", b.id());

        assertStructuredError(putProfile(a, idA, forged), 400, "Bad Request");
        // The rejected write changed nothing, and B gained no profile of their own from it.
        assertThat(json(getAs(a, "/api/v1/fitness-profile/" + idA)).path("display_name").asText())
                .isEqualTo("Alex Morgan");
        assertThat(profilesFor(b.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("an unauthenticated profile request is rejected")
    void profileRequiresAuthentication() throws Exception {
        Session a = register("profile-anon-");
        String id = profileId(a);

        assertUnauthenticated(get("/api/v1/fitness-profile/" + id));
        assertUnauthenticated(get("/api/v1/app-data"));
    }

    /* ---------- Validation ---------- */

    @Test
    @DisplayName("an out-of-range activity_target is rejected with 400")
    void activityTargetIsRangeChecked() throws Exception {
        Session session = register("profile-activity-");
        String id = profileId(session);

        assertStructuredError(putProfile(session, id, Map.of("activity_target", 0)), 400, "Bad Request");
        assertStructuredError(putProfile(session, id, Map.of("activity_target", 15)), 400, "Bad Request");
        // A good value still works, so the bound is not simply rejecting everything.
        assertStatus(putProfile(session, id, Map.of("activity_target", 14)), 200);
    }

    @Test
    @DisplayName("an out-of-range sleep_target_hours is rejected with 400")
    void sleepTargetHoursIsRangeChecked() throws Exception {
        Session session = register("profile-sleep-range-");
        String id = profileId(session);

        assertStructuredError(putProfile(session, id, Map.of("sleep_target_hours", 25)), 400, "Bad Request");
        assertStructuredError(putProfile(session, id, Map.of("sleep_target_hours", -1)), 400, "Bad Request");
        assertStatus(putProfile(session, id, Map.of("sleep_target_hours", 24)), 200);
    }

    @Test
    @DisplayName("a negative calorie_target is rejected with 400")
    void negativeCalorieTargetIsRejected() throws Exception {
        Session session = register("profile-calories-");
        String id = profileId(session);

        assertStructuredError(putProfile(session, id, Map.of("calorie_target", -1)), 400, "Bad Request");
        assertStructuredError(putProfile(session, id, Map.of("protein_target_g", -5)), 400, "Bad Request");
        assertStructuredError(putProfile(session, id, Map.of("water_target_oz", -1)), 400, "Bad Request");
        // Zero is a legitimate value for these and must be accepted.
        assertStatus(putProfile(session, id, Map.of("calorie_target", 0)), 200);
    }

    @Test
    @DisplayName("the remaining numeric bounds are enforced")
    void remainingBoundsAreEnforced() throws Exception {
        Session session = register("profile-bounds-");
        String id = profileId(session);

        assertStructuredError(putProfile(session, id, Map.of("weekly_minutes", 29)), 400, "Bad Request");
        assertStructuredError(putProfile(session, id, Map.of("step_target", 0)), 400, "Bad Request");
        assertStatus(putProfile(session, id, Map.of("weekly_minutes", 30, "step_target", 1)), 200);
    }

    @Test
    @DisplayName("an unknown field is rejected rather than silently ignored")
    void unknownFieldIsRejected() throws Exception {
        Session session = register("profile-unknown-");
        String id = profileId(session);

        // This is the exact failure the sleep_target_hours mismatch produced.
        assertStructuredError(putProfile(session, id, Map.of("sleep_target", 8)), 400, "Bad Request");
    }

    @Test
    @DisplayName("a non-numeric value for a bounded field is a 400, not a 500")
    void nonNumericValueIsRejectedCleanly() throws Exception {
        Session session = register("profile-nonnumeric-");
        String id = profileId(session);

        assertStructuredError(putProfile(session, id, Map.of("calorie_target", "not-a-number")), 400, "Bad Request");
    }
}
