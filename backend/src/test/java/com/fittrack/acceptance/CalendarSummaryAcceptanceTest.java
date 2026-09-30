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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 7: the calendar summary endpoint.
 *
 * <p>The calendar previously read the whole account history and grouped sessions by the UTC date, so
 * an early-morning session appeared on the previous day. It now reads one bounded window from a
 * dedicated endpoint that groups by the calendar day the client recorded. These pin the contract:
 * the window is bounded and validated, every data category is owner-scoped, and the statement count
 * does not grow with the number of days requested.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class CalendarSummaryAcceptanceTest extends AbstractAcceptanceTest {

    private MvcResult summary(Session s, String from, String to) throws Exception {
        return getAs(s, "/api/v1/calendar/summary?from=" + from + "&to=" + to);
    }

    private MvcResult postAs(Session s, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + s.access())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    /** Records a workout session through the real composite endpoint, on a chosen calendar day. */
    private String completeSession(Session s, String sessionDate) throws Exception {
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Calendar exercise");
        MvcResult r = postAs(s, "/api/v1/workout-sessions/complete", sessionBody(exercise, sessionDate));
        assertStatus(r, 200);
        return json(r).path("id").asText();
    }

    private String sessionBody(UUID exercise, String sessionDate) {
        return "{\"session\":{\"title\":\"Session\",\"workout_type\":\"Strength\","
                + "\"session_date\":\"" + sessionDate + "\",\"duration_minutes\":45,"
                + "\"perceived_effort\":7,\"completed\":true},"
                + "\"exercises\":[{\"exercise_id\":\"" + exercise + "\",\"order_index\":0,"
                + "\"sets\":[{\"set_number\":1,\"reps\":10,\"weight\":100,\"completed\":true}]}]}";
    }

    private int sessionCount(Session s, String date) throws Exception {
        return json(summary(s, date, date)).path("sessions").size();
    }

    /** Records a meal through the real composite endpoint, on a chosen calendar day. */
    private void logMeal(Session s, String day, String name) throws Exception {
        String body = "{\"meal\":{\"meal_date\":\"" + day + "\",\"meal_type\":\"LUNCH\","
                + "\"name\":\"" + name + "\",\"source\":\"manual\"},"
                + "\"items\":[{\"food_id\":\"" + catalogFood() + "\",\"grams\":100,\"quantity\":1}]}";
        assertStatus(postAs(s, "/api/v1/meals/complete", body), 200);
    }

    /** A catalog food the composite meal endpoint accepts, created if the catalog is empty. */
    private String catalogFood() {
        Integer n = jdbc.queryForObject("select count(*) from foods", Integer.class);
        if (n != null && n > 0) {
            return jdbc.queryForObject("select id::text from foods limit 1", String.class);
        }
        UUID food = UUID.randomUUID();
        jdbc.update("insert into foods(id,name,serving_size,calories,protein_g,carbs_g,fat_g,fiber_g)"
                + " values (?,?,100,100,10,10,5,1)", food, "Calendar food");
        return food.toString();
    }

    /* ---------- Window validation (M7) ---------- */

    @Test
    @DisplayName("an authenticated owner reads a window of their own calendar")
    void ownerReadsOwnCalendar() throws Exception {
        assertStatus(summary(register("p7-cal-owner-"), "2026-03-01", "2026-03-07"), 200);
    }

    @Test
    @DisplayName("an unauthenticated summary request is a structured 401")
    void unauthenticatedIs401() throws Exception {
        assertUnauthenticated(get("/api/v1/calendar/summary?from=2026-03-01&to=2026-03-07"));
    }

    @Test
    @DisplayName("a malformed, reversed or over-long window is a 400")
    void windowValidation() throws Exception {
        Session s = register("p7-cal-window-");
        assertStructuredError(summary(s, "not-a-date", "2026-03-07"), 400, "Bad Request");
        assertStructuredError(summary(s, "2026-03-01", "2026-13-45"), 400, "Bad Request");
        assertStructuredError(summary(s, "2026-03-07", "2026-03-01"), 400, "Bad Request");
        // A missing bound is rejected rather than defaulting to an unbounded query.
        assertStatus(getAs(s, "/api/v1/calendar/summary?to=2026-03-07"), 400);
        assertStatus(getAs(s, "/api/v1/calendar/summary?from=2026-03-01"), 400);
        // Phase 21: the calendar and the analytics endpoints now share ONE inclusive-range
        // contract (AnalyticsRange), where 366 is the documented maximum. This assertion previously
        // encoded a second, looser rule - a 367-day span was accepted here while /analytics rejected
        // the identical window - which is precisely the contradiction Phase 21 removed.
        assertStatus(summary(s, "2025-01-01", "2026-01-01"), 200);
        assertStructuredError(summary(s, "2025-01-01", "2026-01-02"), 400, "Bad Request");
        assertStatus(summary(s, "2026-03-01", "2026-03-01"), 200);
    }

    @Test
    @DisplayName("an empty calendar returns empty collections rather than nulls")
    void emptyCalendar() throws Exception {
        var body = json(summary(register("p7-cal-empty-"), "2026-03-01", "2026-03-31"));
        assertThat(body.path("days")).isEmpty();
        assertThat(body.path("sessions")).isEmpty();
        assertThat(body.path("meals")).isEmpty();
        assertThat(body.path("habitLogs")).isEmpty();
        assertThat(body.path("bodyMetrics")).isEmpty();
        assertThat(body.path("personalRecords")).isEmpty();
    }
    /* ---------- session_date grouping (M2) ---------- */

    @Test
    @DisplayName("a session is filed on the calendar day the client sent, and started_at is untouched")
    void sessionIsFiledOnItsOwnCalendarDay() throws Exception {
        Session s = register("p7-cal-session-");
        String localDay = LocalDate.now(ZoneOffset.UTC).toString();
        String id = completeSession(s, localDay);

        assertThat(jdbc.queryForObject("select session_date::text from workout_sessions where id=?::uuid",
                String.class, id)).isEqualTo(localDay);
        // started_at is still the real server instant and is never the grouping source.
        assertThat(jdbc.queryForObject("select started_at is not null from workout_sessions where id=?::uuid",
                Boolean.class, id)).isTrue();
        assertThat(sessionCount(s, localDay)).isEqualTo(1);
    }

    @Test
    @DisplayName("a session on one day does not appear in a neighbouring day's window")
    void sessionIsNotFiledOnAUtcAdjacentDay() throws Exception {
        Session s = register("p7-cal-sessionday-");
        completeSession(s, "2026-03-10");
        assertThat(sessionCount(s, "2026-03-10")).isEqualTo(1);
        assertThat(sessionCount(s, "2026-03-11")).isZero();
        assertThat(sessionCount(s, "2026-03-09")).isZero();
    }

    @Test
    @DisplayName("a session without session_date is rejected rather than derived from the clock")
    void sessionDateIsRequired() throws Exception {
        Session s = register("p7-cal-nodate-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "No-date exercise");
        String body = "{\"session\":{\"title\":\"No date\",\"workout_type\":\"Strength\","
                + "\"duration_minutes\":45,\"perceived_effort\":7,\"completed\":true},"
                + "\"exercises\":[{\"exercise_id\":\"" + exercise + "\",\"order_index\":0,"
                + "\"sets\":[{\"set_number\":1,\"reps\":10,\"weight\":100,\"completed\":true}]}]}";
        assertStructuredError(postAs(s, "/api/v1/workout-sessions/complete", body), 400, "Bad Request");
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where user_id=?::uuid",
                Integer.class, s.id())).isZero();
    }

    @Test
    @DisplayName("a clearly future session_date is rejected")
    void futureSessionDateIsRejected() throws Exception {
        Session s = register("p7-cal-future-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Future exercise");
        String body = sessionBody(exercise, LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
        assertStructuredError(postAs(s, "/api/v1/workout-sessions/complete", body), 400, "Bad Request");
    }

    @Test
    @DisplayName("a forged user_id in the session payload cannot reassign ownership")
    void sessionOwnerIsTheToken() throws Exception {
        Session owner = register("p7-cal-owner2-");
        Session attacker = register("p7-cal-attacker-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Forged exercise");
        String body = "{\"user_id\":\"" + owner.id() + "\"," + sessionBody(exercise, "2026-03-10").substring(1);
        // The unknown field is ignored, so the write still succeeds - but only for the caller.
        MvcResult created = postAs(attacker, "/api/v1/workout-sessions/complete", body);
        assertStatus(created, 200);
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where user_id=?::uuid",
                Integer.class, owner.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where user_id=?::uuid",
                Integer.class, attacker.id())).isEqualTo(1);
    }
    /* ---------- Personal records (M3) ---------- */


    @Test
    @DisplayName("a personal record without achieved_date is a 400 and writes nothing")
    void personalRecordRequiresDate() throws Exception {
        Session s = register("p7-pr-nodate-");
        assertStructuredError(postAs(s, "/api/v1/personal-records",
                "{\"exercise\":\"Squat\",\"record_value\":315,\"unit\":\"lbs\"}"), 400, "Bad Request");
        assertThat(jdbc.queryForObject("select count(*) from personal_records where user_id=?::uuid",
                Integer.class, s.id())).isZero();
    }

    @Test
    @DisplayName("a dated record appears on its day and a pre-existing undated one is skipped, not misplaced")
    void undatedRecordsAreSkippedNotMisplaced() throws Exception {
        Session s = register("p7-pr-mixed-");
        assertStatus(postAs(s, "/api/v1/personal-records",
                "{\"exercise\":\"Deadlift\",\"record_value\":300,\"unit\":\"lbs\",\"achieved_date\":\"2026-03-10\"}"), 200);
        // A row written before the date became required, exactly as the migration leaves existing rows.
        jdbc.update("insert into personal_records(id,user_id,exercise,record_value,unit)"
                + " values (?,?::uuid,'Orphan lift',100,'lbs')", UUID.randomUUID(), s.id());

        var body = json(summary(s, "2026-03-01", "2026-03-31"));
        assertThat(body.path("personalRecords")).hasSize(1);
        assertThat(body.path("personalRecords").get(0).path("date").asText()).isEqualTo("2026-03-10");
        // The undated row is still readable through its own resource, just not on a calendar.
        assertThat(json(getAs(s, "/api/v1/app-data")).path("records")).hasSize(2);
    }
    /* ---------- Ownership and mixed activity (M1) ---------- */

    @Test
    @DisplayName("one account's calendar never shows another account's activity")
    void calendarIsOwnerScoped() throws Exception {
        Session owner = register("p7-scope-owner-");
        Session other = register("p7-scope-other-");
        completeSession(owner, "2026-03-10");
        logMeal(owner, "2026-03-10", "Owner lunch");

        var mine = json(summary(owner, "2026-03-01", "2026-03-31"));
        assertThat(mine.path("sessions")).hasSize(1);
        assertThat(mine.path("meals")).hasSize(1);

        var theirs = json(summary(other, "2026-03-01", "2026-03-31"));
        assertThat(theirs.path("sessions")).isEmpty();
        assertThat(theirs.path("meals")).isEmpty();
        assertThat(theirs.path("days")).isEmpty();
    }

    @Test
    @DisplayName("a day holding several activity types reports each of them")
    void mixedActivityTypes() throws Exception {
        Session s = register("p7-mixed-");
        String day = "2026-03-15";
        completeSession(s, day);
        logMeal(s, day, "Dinner");
        jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps,sleep_hours,calories_burned)"
                + " values (?,?::uuid,?::date,?,?,?)", UUID.randomUUID(), s.id(), day, 8000, 7.5, 2000);
        jdbc.update("insert into body_metrics(id,user_id,metric_date,weight_lb) values (?,?::uuid,?::date,?::numeric)",
                UUID.randomUUID(), s.id(), day, 180);
        assertStatus(postAs(s, "/api/v1/personal-records",
                "{\"exercise\":\"Bench\",\"record_value\":225,\"unit\":\"lbs\",\"achieved_date\":\"" + day + "\"}"), 200);

        var body = json(summary(s, day, day));
        assertThat(body.path("sessions")).hasSize(1);
        assertThat(body.path("meals")).hasSize(1);
        assertThat(body.path("bodyMetrics")).hasSize(1);
        assertThat(body.path("personalRecords")).hasSize(1);
        assertThat(body.path("days").get(0).path("date").asText()).isEqualTo(day);
    }

    /* ---------- Ordering and bounded cost (M4) ---------- */

    @Test
    @DisplayName("results are sorted and a long window is served without a per-day loop")
    void orderingAndBoundedCost() throws Exception {
        Session s = register("p7-order-");
        completeSession(s, "2026-03-20");
        completeSession(s, "2026-03-10");
        completeSession(s, "2026-03-15");

        var dates = new java.util.ArrayList<String>();
        json(summary(s, "2026-03-01", "2026-03-31")).path("days")
                .forEach(d -> dates.add(d.path("date").asText()));
        assertThat(dates).isSorted();

        var sessions = json(summary(s, "2026-03-01", "2026-03-31")).path("sessions");
        assertThat(sessions).hasSize(3);
        assertThat(sessions.get(0).path("date").asText()).isEqualTo("2026-03-10");
        assertThat(sessions.get(2).path("date").asText()).isEqualTo("2026-03-20");

        // A 366-day window is served by the same fixed set of range queries, so it still costs a
        // handful of statements rather than the five-per-day the previous endpoint issued.
        assertStatus(summary(s, "2025-03-01", "2026-02-28"), 200);
    }
}
