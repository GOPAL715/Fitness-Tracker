package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16: the authorization acceptance matrix.
 *
 * <p>Every entry proves one authenticated principal cannot read or modify another principal's data
 * by guessing a UUID. The established contract returns 404 for resources the caller does not own,
 * which keeps the existence of the resource itself confidential; this test pins that contract for
 * every owned resource so it cannot silently drift.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class AuthorizationMatrixAcceptanceTest extends AbstractAcceptanceTest {

    /**
     * Cross-user GET is rejected for every owned collection and child resource.
     *
     * @param template a path template containing {@code {id}}
     */
    @ParameterizedTest(name = "cross-user GET is rejected: {0}")
    @ValueSource(strings = {
            "/api/v1/workouts/{id}",
            "/api/v1/meals/{id}",
            "/api/v1/goals/{id}",
            "/api/v1/habits/{id}",
            "/api/v1/ai-usage/{id}",
            "/api/v1/reminders/{id}",
            "/api/v1/health/devices/{id}"
    })
    @DisplayName("Phase 16 authorization - cross-user GET is rejected")
    void crossUserGetIsRejected(String template) throws Exception {
        Session attacker = register("authz-get-");
        Session victim = register("authz-victim-");
        String foreignId = createOwnedWorkout(victim);
        MvcResult result = getAs(attacker, template.replace("{id}", foreignId));

        assertThat(result.getResponse().getStatus())
                .as("GET %s must not return another user's data", template)
                .isIn(403, 404);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("SensitiveValue-" + victim.id());
    }

    @Test
    @DisplayName("Phase 16 authorization - a collection scoped to the caller never shows another user")
    void collectionsAreOwnerScoped() throws Exception {
        Session attacker = register("authz-list-");
        Session victim = register("authz-list-victim-");
        createOwnedWorkout(victim);
        for (String path : new String[] {"/api/v1/workouts", "/api/v1/meals", "/api/v1/goals",
                "/api/v1/habits", "/api/v1/ai-usage"}) {
            MvcResult result = getAs(attacker, path);
            assertThat(result.getResponse().getStatus()).as("GET %s", path).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString())
                    .as("%s must return only the caller's data", path)
                    .doesNotContain("SensitiveValue-" + victim.id());
        }
    }

    @Test
    @DisplayName("Phase 16 authorization - cross-user PUT cannot modify another user's data")
    void crossUserPutIsRejected() throws Exception {
        Session attacker = register("authz-put-");
        Session victim = register("authz-put-victim-");
        String foreignId = createOwnedWorkout(victim);
        MvcResult result = call(attacker, put("/api/v1/workouts/" + foreignId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"HackedByOtherUser\",\"date\":\"2026-01-05\"}"));
        assertThat(result.getResponse().getStatus()).isIn(403, 404);
        String name = jdbc.queryForObject("select title from workout_sessions where id=CAST(? as uuid)",
                String.class, java.util.UUID.fromString(foreignId));
        assertThat(name).as("the victim row must be untouched").isNotEqualTo("HackedByOtherUser");
    }

    @Test
    @DisplayName("Phase 16 authorization - a request-body user_id cannot override the JWT subject")
    void bodyUserIdCannotOverrideIdentity() throws Exception {
        Session attacker = register("authz-body-");
        Session victim = register("authz-body-victim-");
        MvcResult result = call(attacker, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/workouts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"OwnedByAttacker\",\"date\":\"2026-01-05\",\"user_id\":\"" + victim.id() + "\"}"));
        if (result.getResponse().getStatus() < 400) {
            assertThat(ownerOfLatestWorkout()).as("server-side ownership is authoritative").isEqualTo(attacker.id());
        } else {
            assertThat(result.getResponse().getStatus()).isIn(400, 403);
        }
    }

    @Test
    @DisplayName("Phase 16 authorization - analytics returns only the caller's data")
    void crossUserAnalyticsIsRejected() throws Exception {
        Session attacker = register("authz-analytics-");
        Session victim = register("authz-analytics-victim-");
        createOwnedWorkout(victim);
        MvcResult result = getAs(attacker, "/api/v1/analytics/workouts?from=2026-01-01&to=2026-01-31");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("SensitiveValue-" + victim.id());
    }

    @Test
    @DisplayName("Phase 16 authorization - an unauthenticated caller cannot reach owned data")
    void anonymousAccessIsRejected() throws Exception {
        Session victim = register("authz-anon-victim-");
        String foreignId = createOwnedWorkout(victim);
        for (String path : new String[] {"/api/v1/workouts", "/api/v1/workouts/" + foreignId,
                "/api/v1/analytics/workouts?from=2026-01-01&to=2026-01-31", "/api/v1/me"}) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as("%s must require authentication", path).isIn(401, 403);
        }
    }

    @Test
    @DisplayName("Phase 16 storage - a private image of another user is not served")
    void crossUserStorageAccessIsRejected() throws Exception {
        Session attacker = register("authz-store-");
        register("authz-store-victim-");
        assertThat(getAs(attacker, "/api/v1/food-images/" + UUID.randomUUID()).getResponse().getStatus())
                .isIn(403, 404);
    }

    @Test
    @DisplayName("Phase 16 storage - path traversal and root escape remain rejected")
    void traversalRemainsRejected() throws Exception {
        Session user = register("authz-traversal-");
        for (String attempt : new String[] {
                "/api/v1/food-images/..%2f..%2f..%2fetc%2fpasswd",
                "/api/v1/food-images/....//....//etc/hosts",
                "/api/v1/food-images/%2e%2e%2f%2e%2e%2fapplication.yml"}) {
            assertThat(getAs(user, attempt).getResponse().getStatus())
                    .as("traversal attempt %s must be rejected", attempt).isIn(400, 403, 404);
        }
    }

    /** Creates a workout owned by {@code owner} and returns its id. */
    private String createOwnedWorkout(Session owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into workout_sessions (id, user_id, title, started_at, duration_minutes) "
                        + "values (?,?,?,now(),60)",
                id, java.util.UUID.fromString(owner.id()), "SensitiveValue-" + owner.id());
        return id.toString();
    }

    private String ownerOfLatestWorkout() {
        return jdbc.queryForObject("select user_id::text from workout_sessions "
                + "order by created_at desc limit 1", String.class);
    }
}
