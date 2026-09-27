package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 6: habits and habit check-ins.
 *
 * <p>Completing a habit could not be done at all before this phase. habit_logs carries its own NOT
 * NULL user_id, which is the only child table that does, and the generic child create path never
 * supplied it, so every check-in died as a 500. The same shared lookup also reported a missing or
 * foreign parent as a 500 instead of a 404. These pin the behaviour the Habits screen depends on:
 * a habit is a validated record, a check-in belongs to its owner and to an active habit, and no
 * request from one account can touch another account's rows.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class HabitsAcceptanceTest extends AbstractAcceptanceTest {
    @Test
    @DisplayName("a repeated check-in for the same day stays one row instead of failing")
    void duplicateCheckInIsIdempotent() throws Exception {
        Session s = register("p6-dupe-");
        String habit = createHabit(s);
        String first = checkInOk(s, habit, "2026-03-10");

        // The toggle can be replayed on a stale list, so the second POST is accepted and lands on
        // the same row. unique(habit_id, log_date) is what guarantees there is only ever one.
        String second = json(checkIn(s, habit, "2026-03-10")).path("id").asText();

        assertThat(second).isEqualTo(first);
        assertThat(logCount(habit)).isEqualTo(1);

        // A different day is a genuinely new row.
        checkInOk(s, habit, "2026-03-11");
        assertThat(logCount(habit)).isEqualTo(2);
    }

    private MvcResult postAs(Session s, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + s.access())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult putAs(Session s, String path, String body) throws Exception {
        return mvc.perform(put(path).header("Authorization", "Bearer " + s.access())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult deleteAs(Session s, String path) throws Exception {
        return mvc.perform(delete(path).header("Authorization", "Bearer " + s.access())).andReturn();
    }

    /** A habit the server accepts, so a test can vary exactly one field. */
    private String habitJson(String extra) {
        return "{\"name\":\"Drink water\",\"target_per_week\":7" + (extra == null ? "" : "," + extra) + "}";
    }

    private String createHabit(Session s) throws Exception {
        return createHabit(s, habitJson(null));
    }

    private String createHabit(Session s, String body) throws Exception {
        MvcResult r = postAs(s, "/api/v1/habits", body);
        assertStatus(r, 200);
        return json(r).path("id").asText();
    }

    private MvcResult checkIn(Session s, String habitId, String logDate) throws Exception {
        return postAs(s, "/api/v1/habit-logs",
                "{\"habit_id\":\"" + habitId + "\",\"log_date\":\"" + logDate + "\",\"completed\":true}");
    }

    private String checkInOk(Session s, String habitId, String logDate) throws Exception {
        MvcResult r = checkIn(s, habitId, logDate);
        assertStatus(r, 200);
        return json(r).path("id").asText();
    }

    private int logCount(String habitId) {
        return jdbc.queryForObject("select count(*) from habit_logs where habit_id=?::uuid", Integer.class, habitId);
    }

    /* ---------- M1: a check-in can actually be created ---------- */

    @Test
    @DisplayName("a user can check in against their own habit and the row is owned by the token")
    void ownerCanCheckIn() throws Exception {
        Session s = register("p6-checkin-");
        String habit = createHabit(s);

        String logId = checkInOk(s, habit, "2026-03-01");

        // The blocker: user_id is NOT NULL and is stamped from the JWT, never from the request.
        assertThat(jdbc.queryForObject("select user_id::text from habit_logs where id=?::uuid", String.class, logId))
                .isEqualTo(s.id());
        assertThat(jdbc.queryForObject("select habit_id::text from habit_logs where id=?::uuid", String.class, logId))
                .isEqualTo(habit);
        assertThat(jdbc.queryForObject("select log_date::text from habit_logs where id=?::uuid", String.class, logId))
                .isEqualTo("2026-03-01");
        assertThat(jdbc.queryForObject("select completed from habit_logs where id=?::uuid", Boolean.class, logId))
                .isTrue();
    }

    @Test
    @DisplayName("a forged user_id is rejected and never reassigns the row")
    void forgedUserIdIsRejected() throws Exception {
        Session owner = register("p6-forge-owner-");
        Session attacker = register("p6-forge-attacker-");
        String habit = createHabit(owner);

        // A check-in against somebody else's habit is an ownership miss, not a bad request.
        assertStructuredError(checkIn(attacker, habit, "2026-03-02"), 404, "Not Found");
        assertStructuredError(postAs(attacker, "/api/v1/habit-logs",
                "{\"habit_id\":\"" + habit + "\",\"log_date\":\"2026-03-03\",\"completed\":true,\"user_id\":\""
                        + owner.id() + "\"}"), 400, "Bad Request");

        // Nothing was written under either account.
        assertThat(logCount(habit)).isZero();
    }

    /* ---------- M2: an ownership miss is a 404, never a 500 ---------- */

    @Test
    @DisplayName("a check-in against a habit that does not exist is a 404")
    void missingParentCheckInIs404() throws Exception {
        Session s = register("p6-noparent-");
        assertStructuredError(checkIn(s, UUID.randomUUID().toString(), "2026-03-05"), 404, "Not Found");
    }

    @Test
    @DisplayName("a malformed habit id is a 400")
    void malformedParentIdIs400() throws Exception {
        Session s = register("p6-badid-");
        assertStructuredError(checkIn(s, "not-a-uuid", "2026-03-06"), 400, "Bad Request");
    }

    /**
     * M2 changes a lookup shared by every child scope, so the other children are exercised here
     * too: a missing or foreign parent has to read as a 404 rather than a leaked 500.
     */
    @Test
    @DisplayName("the shared parent proof still reports a 404 for the other child scopes")
    void otherChildScopesRejectForeignParents() throws Exception {
        Session owner = register("p6-child-owner-");
        Session other = register("p6-child-other-");
        String session = json(postAs(owner, "/api/v1/workout-sessions",
                "{\"title\":\"Parent\",\"workout_type\":\"Strength\",\"duration_minutes\":20}")).path("id").asText();
        String template = json(postAs(owner, "/api/v1/workout-templates",
                "{\"name\":\"Parent template\",\"workout_type\":\"Strength\",\"estimated_minutes\":20}"))
                .path("id").asText();
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Child probe exercise");

        assertStructuredError(postAs(other, "/api/v1/workout-exercises",
                "{\"workout_session_id\":\"" + UUID.randomUUID() + "\",\"exercise_id\":\"" + exercise
                        + "\",\"order_index\":0}"), 404, "Not Found");
        assertStructuredError(postAs(other, "/api/v1/workout-exercises",
                "{\"workout_session_id\":\"" + session + "\",\"exercise_id\":\"" + exercise
                        + "\",\"order_index\":0}"), 404, "Not Found");
        assertStructuredError(postAs(other, "/api/v1/template-exercises",
                "{\"template_id\":\"" + UUID.randomUUID() + "\",\"exercise_id\":\"" + exercise
                        + "\",\"target_sets\":3,\"target_reps\":\"8-12\"}"), 404, "Not Found");
        assertStructuredError(postAs(other, "/api/v1/template-exercises",
                "{\"template_id\":\"" + template + "\",\"exercise_id\":\"" + exercise
                        + "\",\"target_sets\":3,\"target_reps\":\"8-12\"}"), 404, "Not Found");
    }

    /* ---------- Habit CRUD and ownership ---------- */

    @Test
    @DisplayName("a habit can be created, read, updated and deleted by its owner")
    void habitCrud() throws Exception {
        Session s = register("p6-crud-");
        String habit = createHabit(s);

        assertStatus(getAs(s, "/api/v1/habits/" + habit), 200);
        assertThat(json(getAs(s, "/api/v1/habits/" + habit)).path("name").asText()).isEqualTo("Drink water");

        assertStatus(putAs(s, "/api/v1/habits/" + habit, "{\"name\":\"Drink more water\"}"), 200);
        assertThat(json(getAs(s, "/api/v1/habits/" + habit)).path("name").asText()).isEqualTo("Drink more water");

        assertStatus(deleteAs(s, "/api/v1/habits/" + habit), 200);
        assertStatus(getAs(s, "/api/v1/habits/" + habit), 404);
    }

    @Test
    @DisplayName("one account cannot read, change or delete another account's habit or log")
    void habitsAndLogsAreOwnerScoped() throws Exception {
        Session owner = register("p6-scope-owner-");
        Session other = register("p6-scope-other-");
        String habit = createHabit(owner);
        String log = checkInOk(owner, habit, "2026-03-07");

        assertStructuredError(getAs(other, "/api/v1/habits/" + habit), 404, "Not Found");
        assertStructuredError(putAs(other, "/api/v1/habits/" + habit, "{\"name\":\"Taken\"}"), 404, "Not Found");
        assertStructuredError(deleteAs(other, "/api/v1/habits/" + habit), 404, "Not Found");
        assertStructuredError(getAs(other, "/api/v1/habit-logs/" + log), 404, "Not Found");
        assertStructuredError(deleteAs(other, "/api/v1/habit-logs/" + log), 404, "Not Found");

        // The owner's rows are untouched by the rejected attempts.
        assertStatus(getAs(owner, "/api/v1/habits/" + habit), 200);
        assertStatus(getAs(owner, "/api/v1/habit-logs/" + log), 200);
    }

    @Test
    @DisplayName("the habit list only ever contains the caller's own habits")
    void habitListIsScoped() throws Exception {
        Session owner = register("p6-list-owner-");
        Session other = register("p6-list-other-");
        createHabit(owner);

        assertThat(json(getAs(owner, "/api/v1/habits"))).hasSize(1);
        assertThat(json(getAs(other, "/api/v1/habits"))).isEmpty();
    }

    @Test
    @DisplayName("habit routes require authentication")
    void habitRoutesRequireAuth() throws Exception {
        Session s = register("p6-auth-");
        String habit = createHabit(s);
        String log = checkInOk(s, habit, "2026-03-08");

        assertUnauthenticated(get("/api/v1/habits"));
        assertUnauthenticated(post("/api/v1/habits").contentType(MediaType.APPLICATION_JSON).content(habitJson(null)));
        assertUnauthenticated(get("/api/v1/habit-logs"));
        assertUnauthenticated(post("/api/v1/habit-logs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"habit_id\":\"" + habit + "\",\"log_date\":\"2026-03-09\",\"completed\":true}"));
        assertUnauthenticated(get("/api/v1/habits/" + habit));
        assertUnauthenticated(delete("/api/v1/habit-logs/" + log));
    }

    /* ---------- M5: habit validation ---------- */

    @Test
    @DisplayName("a habit needs a name and a target of at least one")
    void habitValidationOnCreate() throws Exception {
        Session s = register("p6-validate-");
        for (String body : new String[] {
                "{\"target_per_week\":7}",
                "{\"name\":\"\",\"target_per_week\":7}",
                "{\"name\":\"   \",\"target_per_week\":7}",
                "{\"name\":\"No target\"}",
                "{\"name\":\"Zero\",\"target_per_week\":0}",
                "{\"name\":\"Negative\",\"target_per_week\":-3}",
                "{\"name\":\"Fractional\",\"target_per_week\":2.5}" }) {
            assertStructuredError(postAs(s, "/api/v1/habits", body), 400, "Bad Request");
        }
        assertThat(json(getAs(s, "/api/v1/habits"))).isEmpty();
    }

    @Test
    @DisplayName("a valid habit is accepted, including a target above seven")
    void validHabitIsAccepted() throws Exception {
        Session s = register("p6-valid-");
        createHabit(s, "{\"name\":\"Tidy up\",\"target_per_week\":14}");
        // No arbitrary weekly maximum: the product has never defined one.
        createHabit(s, "{\"name\":\"Once in a while\",\"target_per_week\":1}");
        assertThat(json(getAs(s, "/api/v1/habits"))).hasSize(2);
    }

    @Test
    @DisplayName("an update applies the same rules, and a partial patch stays valid")
    void habitValidationOnUpdate() throws Exception {
        Session s = register("p6-validate-update-");
        String habit = createHabit(s);

        assertStructuredError(putAs(s, "/api/v1/habits/" + habit, "{\"name\":\"  \"}"), 400, "Bad Request");
        assertStructuredError(putAs(s, "/api/v1/habits/" + habit, "{\"target_per_week\":0}"), 400, "Bad Request");
        assertStructuredError(putAs(s, "/api/v1/habits/" + habit, "{\"target_per_week\":-1}"), 400, "Bad Request");

        // A rejected patch leaves the stored habit as it was.
        assertThat(json(getAs(s, "/api/v1/habits/" + habit)).path("name").asText()).isEqualTo("Drink water");

        // A partial patch that does not touch the validated fields is still allowed.
        assertStatus(putAs(s, "/api/v1/habits/" + habit, "{\"color\":\"#ff0000\"}"), 200);
        assertThat(json(getAs(s, "/api/v1/habits/" + habit)).path("name").asText()).isEqualTo("Drink water");
    }

    /* ---------- Duplicates and un-checking ---------- */


    @Test
    @DisplayName("un-checking removes the row and the day can be checked in again")
    void uncheckDeletesTheLog() throws Exception {
        Session s = register("p6-uncheck-");
        String habit = createHabit(s);
        String log = checkInOk(s, habit, "2026-03-11");

        assertStatus(deleteAs(s, "/api/v1/habit-logs/" + log), 200);
        assertThat(logCount(habit)).isZero();

        // The toggle can go back the other way afterwards.
        checkInOk(s, habit, "2026-03-11");
        assertThat(logCount(habit)).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed log date is a 400")
    void malformedLogDateIs400() throws Exception {
        Session s = register("p6-baddate-");
        String habit = createHabit(s);
        assertStructuredError(checkIn(s, habit, "not-a-date"), 400, "Bad Request");
        assertStructuredError(checkIn(s, habit, "2026-13-45"), 400, "Bad Request");
    }

    /* ---------- M7: a future date is refused ---------- */

    @Test
    @DisplayName("today and past are accepted, a clearly future date is a 400")
    void futureCheckInIsRefused() throws Exception {
        Session s = register("p6-future-");
        String habit = createHabit(s);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // The client sends its own calendar date, so "today" is the server's date here. A real
        // user's local today is never more than a day ahead of it, which the bound must allow.
        checkInOk(s, habit, today.toString());
        checkInOk(s, habit, today.minusDays(1).toString());
        checkInOk(s, habit, today.plusDays(1).toString());
        assertStructuredError(checkIn(s, habit, today.plusDays(30).toString()), 400, "Bad Request");
        assertThat(logCount(habit)).isEqualTo(3);
    }

    /* ---------- M8: an inactive habit takes no new check-ins ---------- */

    @Test
    @DisplayName("deactivating blocks new check-ins but keeps the history, and reactivating restores it")
    void inactiveHabitBlocksNewCheckIns() throws Exception {
        Session s = register("p6-inactive-");
        String habit = createHabit(s);
        String kept = checkInOk(s, habit, "2026-03-12");

        assertStatus(putAs(s, "/api/v1/habits/" + habit, "{\"active\":false}"), 200);
        assertStructuredError(checkIn(s, habit, "2026-03-13"), 400, "Bad Request");

        // The history is untouched, and still readable and deletable.
        assertThat(logCount(habit)).isEqualTo(1);
        assertStatus(getAs(s, "/api/v1/habit-logs/" + kept), 200);
        assertStatus(deleteAs(s, "/api/v1/habit-logs/" + kept), 200);

        // Reactivating makes logging work again, with no other change.
        assertStatus(putAs(s, "/api/v1/habits/" + habit, "{\"active\":true}"), 200);
        checkInOk(s, habit, "2026-03-14");
        assertThat(logCount(habit)).isEqualTo(1);
    }

    /* ---------- Delete cascades, and app-data reflects both sides ---------- */

    @Test
    @DisplayName("deleting a habit removes its logs through the foreign key and leaves other rows alone")
    void deletingAHabitCascadesItsLogs() throws Exception {
        Session owner = register("p6-cascade-owner-");
        Session other = register("p6-cascade-other-");
        String habit = createHabit(owner);
        checkInOk(owner, habit, "2026-03-15");
        checkInOk(owner, habit, "2026-03-16");
        String otherHabit = createHabit(other);
        checkInOk(other, otherHabit, "2026-03-15");

        assertStatus(deleteAs(owner, "/api/v1/habits/" + habit), 200);

        assertThat(logCount(habit)).isZero();
        assertThat(logCount(otherHabit)).isEqualTo(1);
        assertStatus(getAs(other, "/api/v1/habits/" + otherHabit), 200);
    }

    @Test
    @DisplayName("app-data reports a new check-in and drops it again once it is removed")
    void appDataTracksHabitLogs() throws Exception {
        Session s = register("p6-appdata-");
        String habit = createHabit(s);
        assertThat(json(getAs(s, "/api/v1/app-data")).path("habits")).hasSize(1);
        assertThat(json(getAs(s, "/api/v1/app-data")).path("habitLogs")).isEmpty();

        String log = checkInOk(s, habit, "2026-03-17");
        assertThat(json(getAs(s, "/api/v1/app-data")).path("habitLogs")).hasSize(1);

        assertStatus(deleteAs(s, "/api/v1/habit-logs/" + log), 200);
        assertThat(json(getAs(s, "/api/v1/app-data")).path("habitLogs")).isEmpty();
    }
}
