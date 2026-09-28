package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import java.time.LocalDate;

/**
 * Phase 4: workout and session logging acceptance tests.
 *
 * <p>Three things are proven. A completed session records a server-generated {@code started_at},
 * because the calendar, set tracking and metrics all read that column and a null value made those
 * screens throw. The workout bounds reject nonsense before it reaches PostgreSQL. And the template
 * update is genuinely all-or-nothing, rather than a sequence that could leave a template empty.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class WorkoutSessionAcceptanceTest extends AbstractAcceptanceTest {

    /** A catalog row to reference, since the exercise catalog ships empty. */
    private UUID exercise(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name,muscle_group,equipment,difficulty,is_compound) values (?,?,?,?,?,?)",
                id, name, "Chest", "Barbell", "Intermediate", true);
        return id;
    }

    private MvcResult completeSession(Session session, String body) throws Exception {
        return mvc.perform(post("/api/v1/workout-sessions/complete")
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private MvcResult postWorkout(Session session, String body) throws Exception {
        return mvc.perform(post("/api/v1/workouts")
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private String sessionBody(UUID exerciseId, String title) {
        return """
                {"session":{"title":"%s","workout_type":"Strength","session_date":"%s","duration_minutes":45,
                 "perceived_effort":7,"notes":null,"completed":true},
                 "exercises":[{"exercise_id":"%s","order_index":0,"notes":null,
                 "sets":[{"set_number":1,"reps":8,"weight":135.0,"rpe":7.0,"completed":true},
                         {"set_number":2,"reps":6,"weight":145.0,"rpe":8.0,"completed":true}]}]}
                """.formatted(title, LocalDate.now().toString(), exerciseId);
    }

    /* ---------- M1: started_at ---------- */

    @Test
    @DisplayName("a completed session records a server-generated started_at")
    void completedSessionRecordsStartedAt() throws Exception {
        UUID ex = exercise("Started At Lift");
        Session session = register("phase4-started-");

        MvcResult result = completeSession(session, sessionBody(ex, "Started At Session"));
        assertStatus(result, 200);

        String id = json(result).path("id").asText();
        // The stored row, not just the response.
        assertThat(jdbc.queryForObject("select started_at from workout_sessions where id=CAST(? as uuid)",
                java.time.OffsetDateTime.class, id)).as("started_at must be populated").isNotNull();
    }

    @Test
    @DisplayName("app-data returns the same non-null started_at the session was created with")
    void appDataReturnsStartedAt() throws Exception {
        UUID ex = exercise("App Data Started");
        Session session = register("phase4-appdata-");

        String id = json(completeSession(session, sessionBody(ex, "App Data Session"))).path("id").asText();
        JsonNode sessions = json(getAs(session, "/api/v1/app-data")).path("sessions");
        JsonNode match = null;
        for (JsonNode row : sessions) {
            if (id.equals(row.path("id").asText())) match = row;
        }

        assertThat(match).as("the session is returned by app-data").isNotNull();
        assertThat(match.path("started_at").isNull())
                .as("the frontend calls started_at.slice(), so it must never be null").isFalse();
        assertThat(match.path("started_at").asText()).isNotBlank();
    }

    @Test
    @DisplayName("started_at is set from the server, never taken from the request")
    void startedAtIsServerOwned() throws Exception {
        UUID ex = exercise("Server Owned");
        Session session = register("phase4-serverowned-");

        String id = json(completeSession(session, sessionBody(ex, "Server Owned Session"))).path("id").asText();
        // Read as a real timestamp rather than text, so the driver parses it.
        java.time.OffsetDateTime stored = jdbc.queryForObject(
                "select started_at from workout_sessions where id=CAST(? as uuid)",
                java.time.OffsetDateTime.class, id);
        assertThat(stored).isNotNull();
        // Within a minute of now, i.e. the server's clock rather than a replayed client value.
        assertThat(Math.abs(System.currentTimeMillis() - stored.toInstant().toEpochMilli()))
                .as("started_at is the server time").isLessThan(60_000L);
    }
    /* ---------- M2: workout bounds ---------- */

    @Test
    @DisplayName("workout duration is bounded to 0..1440")
    void workoutDurationIsBounded() throws Exception {
        Session session = register("phase4-duration-");
        assertStatus(postWorkout(session, "{\"title\":\"D\",\"duration_minutes\":-1}"), 400);
        assertStatus(postWorkout(session, "{\"title\":\"D\",\"duration_minutes\":1441}"), 400);
        assertStatus(postWorkout(session, "{\"title\":\"D\",\"duration_minutes\":0}"), 200);
        assertStatus(postWorkout(session, "{\"title\":\"D\",\"duration_minutes\":1440}"), 200);
    }

    @Test
    @DisplayName("workout calories cannot be negative")
    void workoutCaloriesCannotBeNegative() throws Exception {
        Session session = register("phase4-calories-");
        assertStatus(postWorkout(session, "{\"title\":\"C\",\"calories_burned\":-1}"), 400);
        assertStatus(postWorkout(session, "{\"title\":\"C\",\"calories_burned\":0}"), 200);
    }

    @Test
    @DisplayName("perceived effort is bounded to 1..10")
    void perceivedEffortIsBounded() throws Exception {
        Session session = register("phase4-effort-");
        assertStatus(postWorkout(session, "{\"title\":\"E\",\"perceived_effort\":0}"), 400);
        assertStatus(postWorkout(session, "{\"title\":\"E\",\"perceived_effort\":11}"), 400);
        assertStatus(postWorkout(session, "{\"title\":\"E\",\"perceived_effort\":1}"), 200);
        assertStatus(postWorkout(session, "{\"title\":\"E\",\"perceived_effort\":10}"), 200);
    }

    @Test
    @DisplayName("distance cannot be negative")
    void distanceCannotBeNegative() throws Exception {
        Session session = register("phase4-distance-");
        assertStatus(postWorkout(session, "{\"title\":\"M\",\"distance_miles\":-0.5}"), 400);
        assertStatus(postWorkout(session, "{\"title\":\"M\",\"distance_miles\":3.5}"), 200);
    }

    @Test
    @DisplayName("the same bounds apply when a workout is updated")
    void workoutUpdateIsBounded() throws Exception {
        Session session = register("phase4-update-");
        String id = json(postWorkout(session, "{\"title\":\"U\",\"duration_minutes\":30}")).path("id").asText();

        MvcResult bad = mvc.perform(put("/api/v1/workouts/" + id)
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"duration_minutes\":-5}")).andReturn();
        assertStructuredError(bad, 400, "Bad Request");

        MvcResult good = mvc.perform(put("/api/v1/workouts/" + id)
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"duration_minutes\":45}")).andReturn();
        assertStatus(good, 200);
    }

    @Test
    @DisplayName("a null bound value is allowed, so an optional column can be cleared")
    void nullBoundsAreAllowed() throws Exception {
        // A bound must not force a value where the domain genuinely allows "not recorded".
        Session session = register("phase4-null-");
        assertStatus(postWorkout(session,
                "{\"title\":\"N\",\"duration_minutes\":null,\"perceived_effort\":null,\"calories_burned\":null}"), 200);
    }

    @Test
    @DisplayName("sets are only created through the composite endpoint, which bounds them itself")
    void setsHaveNoGenericWritePath() throws Exception {
        // exercise-sets is intentionally absent from the generic write mappings, so a set can never be
        // written without DTO validation. This pins that contract.
        Session session = register("phase4-nogenericsets-");
        assertUnauthenticated(post("/api/v1/exercise-sets"));
        MvcResult result = mvc.perform(post("/api/v1/exercise-sets")
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"set_number\":1,\"reps\":5}")).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("no generic route exists for exercise-sets").isEqualTo(404);
    }
    /* ---------- M3: atomic template update ---------- */

    private MvcResult updateTemplate(Session session, String id, String body) throws Exception {
        return mvc.perform(put("/api/v1/workout-templates/" + id + "/complete")
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private UUID createTemplate(Session session, UUID exerciseId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/workout-templates/complete")
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"template":{"name":"Original","description":"First","workout_type":"Strength",
                         "estimated_minutes":30,"favorite":false},
                         "exercises":[{"exercise_id":"%s","order_index":0,"target_sets":3,
                         "target_reps":"8-12","target_weight":null}]}
                        """.formatted(exerciseId))).andReturn();
        assertStatus(result, 200);
        return UUID.fromString(json(result).path("id").asText());
    }

    /** A valid update body, so a test can change only the part it is actually asserting. */
    private String templateUpdate(UUID exerciseId, String name) {
        return """
                {"template":{"name":"%s","description":"Updated","workout_type":"Hypertrophy",
                 "estimated_minutes":50,"favorite":false},
                 "exercises":[{"exercise_id":"%s","order_index":0,"target_sets":4,
                 "target_reps":"10-12","target_weight":100.0}]}
                """.formatted(name, exerciseId);
    }

    @Test
    @DisplayName("a template update replaces the fields and the child exercises")
    void templateUpdateReplacesFieldsAndChildren() throws Exception {
        UUID first = exercise("Update Exercise One");
        UUID second = exercise("Update Exercise Two");
        Session session = register("phase4-tplupdate-");
        UUID id = createTemplate(session, first);

        MvcResult result = updateTemplate(session, id.toString(), templateUpdate(second, "Renamed"));
        assertStatus(result, 200);
        assertThat(json(result).path("id").asText()).isEqualTo(id.toString());
        assertThat(json(result).path("child_count").asInt()).isEqualTo(1);

        JsonNode template = json(getAs(session, "/api/v1/workout-templates/" + id));
        assertThat(template.path("name").asText()).isEqualTo("Renamed");
        assertThat(template.path("estimated_minutes").asInt()).isEqualTo(50);
        assertThat(template.path("is_favorite").asBoolean()).isFalse();

        // The old child is gone and the new one present, so the list is replaced, not appended.
        List<Map<String, Object>> children = jdbc.queryForList(
                "select exercise_id,target_sets from workout_template_exercises where template_id=?", id);
        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("exercise_id")).isEqualTo(second);
        assertThat(((Number) children.get(0).get("target_sets")).intValue()).isEqualTo(4);
    }

    @Test
    @DisplayName("a rejected exercise rolls the whole template update back")
    void templateUpdateRollsBackOnBadExercise() throws Exception {
        UUID good = exercise("Rollback Good");
        Session session = register("phase4-tplrollback-");
        UUID id = createTemplate(session, good);

        // The parent update would succeed on its own; only the child reference is invalid.
        MvcResult result = updateTemplate(session, id.toString(), """
                {"template":{"name":"Should Not Persist","description":"x","workout_type":"Strength",
                 "estimated_minutes":99,"favorite":true},
                 "exercises":[{"exercise_id":"00000000-0000-0000-0000-000000000000","order_index":0,
                 "target_sets":3,"target_reps":"8-12","target_weight":null}]}
                """);
        assertStructuredError(result, 404, "Not Found");

        // Neither the parent nor the children moved.
        assertThat(jdbc.queryForObject("select name from workout_templates where id=?", String.class, id))
                .isEqualTo("Original");
        assertThat(jdbc.queryForObject("select estimated_minutes from workout_templates where id=?", Integer.class, id))
                .isEqualTo(30);
        assertThat(jdbc.queryForObject("select count(*) from workout_template_exercises where template_id=?",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from workout_template_exercises where template_id=? and exercise_id=?",
                Integer.class, id, good)).isEqualTo(1);
    }

    @Test
    @DisplayName("another user cannot update someone else's template")
    void templateUpdateIsOwnerScoped() throws Exception {
        UUID ex = exercise("Owner Scoped");
        Session owner = register("phase4-tplowner-");
        Session other = register("phase4-tplother-");
        UUID id = createTemplate(owner, ex);

        assertStructuredError(updateTemplate(other, id.toString(), templateUpdate(ex, "Hijacked")), 404, "Not Found");
        assertThat(jdbc.queryForObject("select name from workout_templates where id=?", String.class, id))
                .isEqualTo("Original");
    }
    @Test
    @DisplayName("a forged user_id cannot move a template to another account")
    void forgedUserIdCannotChangeOwnership() throws Exception {
        UUID ex = exercise("Forged Owner");
        Session owner = register("phase4-tplforged-");
        Session victim = register("phase4-tplvictim-");
        UUID id = createTemplate(owner, ex);

        MvcResult result = updateTemplate(owner, id.toString(), """
                {"user_id":"%s","template":{"name":"Forged","description":"x","workout_type":"Strength",
                 "estimated_minutes":10,"favorite":false},
                 "exercises":[{"exercise_id":"%s","order_index":0,"target_sets":3,
                 "target_reps":"8-12","target_weight":null}]}
                """.formatted(victim.id(), ex));
        assertStatus(result, 200);

        // Still owned by the caller, never reassigned to the forged subject.
        assertThat(jdbc.queryForObject("select user_id from workout_templates where id=?", UUID.class, id))
                .isEqualTo(UUID.fromString(owner.id()));
        assertThat(jdbc.queryForObject("select count(*) from workout_templates where user_id=CAST(? as uuid)",
                Integer.class, victim.id())).isZero();
    }

    @Test
    @DisplayName("a malformed template id is a client error, not a server fault")
    void malformedTemplateIdIsRejected() throws Exception {
        UUID ex = exercise("Malformed Id");
        Session session = register("phase4-badid-");
        MvcResult result = updateTemplate(session, "not-a-uuid", templateUpdate(ex, "Bad"));
        assertStructuredError(result, 400, "Bad Request");
    }

    @Test
    @DisplayName("an empty exercise list is rejected before anything is written")
    void emptyTemplateExerciseListIsRejected() throws Exception {
        UUID ex = exercise("Empty List");
        Session session = register("phase4-tplempty-");
        UUID id = createTemplate(session, ex);

        MvcResult result = updateTemplate(session, id.toString(), """
                {"template":{"name":"Empty","description":"x","workout_type":"Strength",
                 "estimated_minutes":10,"favorite":false},"exercises":[]}
                """);
        assertStructuredError(result, 400, "Bad Request");
        assertThat(jdbc.queryForObject("select count(*) from workout_template_exercises where template_id=?",
                Integer.class, id)).isEqualTo(1);
    }

    /* ---------- Ownership of the session chain ---------- */

    @Test
    @DisplayName("a completed session is only reachable by its owner")
    void completedSessionIsOwnerScoped() throws Exception {
        UUID ex = exercise("Chain Lift");
        Session owner = register("phase4-chainowner-");
        Session other = register("phase4-chainother-");

        String id = json(completeSession(owner, sessionBody(ex, "Chain Session"))).path("id").asText();
        assertStatus(getAs(owner, "/api/v1/workout-sessions/" + id), 200);
        assertStatus(getAs(other, "/api/v1/workout-sessions/" + id), 404);

        MvcResult del = mvc.perform(delete("/api/v1/workout-sessions/" + id)
                .header("Authorization", "Bearer " + other.access())).andReturn();
        assertThat(del.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where id=CAST(? as uuid)",
                Integer.class, id)).isEqualTo(1);
    }
}
