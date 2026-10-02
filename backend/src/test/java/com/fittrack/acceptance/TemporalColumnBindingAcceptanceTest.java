package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 23C: the generic owned-resource binder must hand temporal columns a typed JDBC value.
 *
 * <p>Both defects this covers had the same shape. {@code reminders.quiet_hours_start} and
 * {@code quiet_hours_end} are {@code time} columns whose names end in {@code _start} and
 * {@code _end}, so the old {@code endsWith("_time")} rule never converted them and the raw request
 * string reached the driver, which rejected it as a grammar error and surfaced as an opaque 500.
 * {@code workout_sessions.started_at} and {@code completed_at} are {@code timestamptz} columns that
 * no rule covered at all, for the same reason.
 *
 * <p>The reminder half is the one a user could actually hit: the Habits screen posts
 * {@code quiet_hours_start: "22:00"}, so quiet hours were unreachable through the API. These tests
 * therefore assert the stored value as well as the status, because a 2xx alone would not prove the
 * column received the right time.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class TemporalColumnBindingAcceptanceTest extends AbstractAcceptanceTest {

    private MvcResult postReminder(Session user, Map<String, Object> body) throws Exception {
        return call(user, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private Map<String, Object> reminderWithQuietHours(String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "water");
        body.put("title", "Evening wind down");
        body.put("message", "Water");
        body.put("scheduled_time", "09:00");
        body.put("timezone", "Asia/Kolkata");
        body.put("recurrence", "daily");
        body.put("enabled", true);
        if (start != null) body.put("quiet_hours_start", start);
        if (end != null) body.put("quiet_hours_end", end);
        return body;
    }

    // -------------------------------------------------- reminder quiet hours

    @Test
    @DisplayName("Phase 23C reminders - quiet hours are accepted and persisted on create")
    void quietHoursPersistOnCreate() throws Exception {
        Session user = register("p23c-rem-create-");

        MvcResult result = postReminder(user, reminderWithQuietHours("22:00", "06:00"));

        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString())
                .isIn(200, 201);
        UUID id = UUID.fromString(json(result).path("id").asText());
        // Asserted against the row, not the response body: the read path formats these for the UI,
        // and only the stored column proves the value was bound as a time rather than accepted as
        // an opaque success.
        assertThat(jdbc.queryForObject("select quiet_hours_start from reminders where id=?", Time.class, id))
                .isEqualTo(Time.valueOf("22:00:00"));
        assertThat(jdbc.queryForObject("select quiet_hours_end from reminders where id=?", Time.class, id))
                .isEqualTo(Time.valueOf("06:00:00"));
    }

    @Test
    @DisplayName("Phase 23C reminders - quiet hours can be changed by update")
    void quietHoursPersistOnUpdate() throws Exception {
        Session user = register("p23c-rem-update-");
        UUID id = UUID.fromString(json(postReminder(user, reminderWithQuietHours("22:00", "06:00")))
                .path("id").asText());

        MvcResult result = call(user, put("/api/v1/reminders/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("quiet_hours_start", "23:30", "quiet_hours_end", "05:15"))));

        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString())
                .isIn(200, 204);
        assertThat(jdbc.queryForObject("select quiet_hours_start from reminders where id=?", Time.class, id))
                .isEqualTo(Time.valueOf("23:30:00"));
        assertThat(jdbc.queryForObject("select quiet_hours_end from reminders where id=?", Time.class, id))
                .isEqualTo(Time.valueOf("05:15:00"));
    }

    @Test
    @DisplayName("Phase 23C reminders - seconds precision is still accepted, as before")
    void quietHoursAcceptSeconds() throws Exception {
        Session user = register("p23c-rem-secs-");

        MvcResult result = postReminder(user, reminderWithQuietHours("22:00:30", "06:15:45"));

        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString())
                .isIn(200, 201);
        UUID id = UUID.fromString(json(result).path("id").asText());
        assertThat(jdbc.queryForObject("select quiet_hours_start from reminders where id=?", Time.class, id))
                .isEqualTo(Time.valueOf("22:00:30"));
    }

    @Test
    @DisplayName("Phase 23C reminders - a malformed quiet hour is a 400, not a database 500")
    void malformedQuietHourIsRejectedAsBadRequest() throws Exception {
        Session user = register("p23c-rem-bad-");

        for (String bad : new String[]{"not-a-time", "25:00", "9", "22:00:00:00", "24:00", "12:60"}) {
            MvcResult result = postReminder(user, reminderWithQuietHours(bad, "06:00"));

            assertStructuredError(result, 400, "Bad Request");
            assertThat(json(result).path("message").asText()).contains("quiet_hours_start");
        }
    }

    @Test
    @DisplayName("Phase 23C reminders - an out-of-range hour is rejected, not silently rolled over")
    void outOfRangeHourIsNotNormalised() throws Exception {
        Session user = register("p23c-rem-range-");

        // The first implementation padded "25:00" to "25:00:00" and PostgreSQL stored that as
        // 01:00:00, so a typo became a different reminder window instead of an error. This test
        // exists to keep that from coming back.
        MvcResult result = postReminder(user, reminderWithQuietHours("25:00", "06:00"));

        assertStructuredError(result, 400, "Bad Request");
        assertThat(jdbc.queryForObject("select count(*) from reminders where user_id=CAST(? as uuid)",
                Integer.class, user.id()))
                .as("the rejected create must not have left a row behind")
                .isZero();
    }

    @Test
    @DisplayName("Phase 23C reminders - omitted quiet hours still leave both columns null")
    void omittedQuietHoursRemainNull() throws Exception {
        Session user = register("p23c-rem-null-");

        MvcResult result = postReminder(user, reminderWithQuietHours(null, null));

        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        UUID id = UUID.fromString(json(result).path("id").asText());
        assertThat(jdbc.queryForObject("select quiet_hours_start from reminders where id=?", Time.class, id))
                .as("an absent value stays absent rather than being defaulted to midnight")
                .isNull();
        assertThat(jdbc.queryForObject("select quiet_hours_end from reminders where id=?", Time.class, id))
                .isNull();
    }

    @Test
    @DisplayName("Phase 23C reminders - a reminder without quiet hours is unchanged")
    void reminderWithoutQuietHoursIsUnchanged() throws Exception {
        Session user = register("p23c-rem-plain-");

        MvcResult result = postReminder(user, Map.of(
                "type", "workout", "title", "Time to train", "message", "Session starts now",
                "scheduled_time", "06:00", "timezone", "UTC", "recurrence", "daily", "enabled", true));

        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        UUID id = UUID.fromString(json(result).path("id").asText());
        // scheduled_time was already covered by the old suffix rule; it must keep working, because
        // the fix replaced the rule rather than adding to it.
        assertThat(jdbc.queryForObject("select scheduled_time from reminders where id=?", Time.class, id))
                .isEqualTo(Time.valueOf("06:00:00"));
        assertThat(jdbc.queryForObject("select title from reminders where id=?", String.class, id))
                .isEqualTo("Time to train");
    }

    @Test
    @DisplayName("Phase 23C reminders - a malformed scheduled_time is a 400 too")
    void malformedScheduledTimeIsRejectedAsBadRequest() throws Exception {
        Session user = register("p23c-rem-schedbad-");

        MvcResult result = postReminder(user, Map.of(
                "type", "water", "title", "Bad schedule", "scheduled_time", "half past six",
                "timezone", "UTC", "recurrence", "daily"));

        assertStructuredError(result, 400, "Bad Request");
    }

    // -------------------------------------------------- workout session instants

    @Test
    @DisplayName("Phase 23C workout sessions - started_at and completed_at persist on create")
    void sessionTimestampsPersist() throws Exception {
        Session user = register("p23c-ws-create-");

        MvcResult result = call(user, post("/api/v1/workout-sessions")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "title", "Evening lift", "workout_type", "Strength",
                        "started_at", "2026-01-05T07:30:00Z", "completed_at", "2026-01-05T08:15:00Z",
                        "duration_minutes", 45))));

        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString())
                .isIn(200, 201);
        UUID id = UUID.fromString(json(result).path("id").asText());
        assertThat(jdbc.queryForObject("select started_at from workout_sessions where id=?", Timestamp.class, id)
                .toInstant()).isEqualTo(Instant.parse("2026-01-05T07:30:00Z"));
        assertThat(jdbc.queryForObject("select completed_at from workout_sessions where id=?", Timestamp.class, id)
                .toInstant()).isEqualTo(Instant.parse("2026-01-05T08:15:00Z"));
    }

    @Test
    @DisplayName("Phase 23C workout sessions - an offset timestamp keeps the instant it denotes")
    void offsetTimestampsPreserveTheInstant() throws Exception {
        Session user = register("p23c-ws-offset-");

        MvcResult result = call(user, post("/api/v1/workout-sessions")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "title", "Offset session",
                        "started_at", "2026-01-05T13:00:00+05:30",
                        "completed_at", "2026-01-05T14:00:00+05:30"))));

        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString())
                .isIn(200, 201);
        UUID id = UUID.fromString(json(result).path("id").asText());
        // 13:00+05:30 is 07:30Z. Reading it as a bare wall clock would store 13:00Z and silently
        // shift the session by five and a half hours, so the assertion is on the instant.
        assertThat(jdbc.queryForObject("select started_at from workout_sessions where id=?", Timestamp.class, id)
                .toInstant()).isEqualTo(Instant.parse("2026-01-05T07:30:00Z"));
        assertThat(jdbc.queryForObject("select completed_at from workout_sessions where id=?", Timestamp.class, id)
                .toInstant()).isEqualTo(Instant.parse("2026-01-05T08:30:00Z"));
    }

    @Test
    @DisplayName("Phase 23C workout sessions - a timestamp without an offset is read as UTC")
    void offsetlessTimestampsAreReadAsUtc() throws Exception {
        Session user = register("p23c-ws-naive-");

        MvcResult result = call(user, post("/api/v1/workout-sessions")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "title", "Local session", "started_at", "2026-01-05T07:30:00"))));

        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString())
                .isIn(200, 201);
        UUID id = UUID.fromString(json(result).path("id").asText());
        // Matches hibernate.jdbc.time_zone=UTC, so the stored instant is the wall clock the client wrote.
        assertThat(jdbc.queryForObject("select started_at from workout_sessions where id=?", Timestamp.class, id)
                .toInstant()).isEqualTo(OffsetDateTime.of(2026, 1, 5, 7, 30, 0, 0, ZoneOffset.UTC).toInstant());
    }

    @Test
    @DisplayName("Phase 23C workout sessions - a malformed timestamp is a 400, not a database 500")
    void malformedTimestampIsRejectedAsBadRequest() throws Exception {
        Session user = register("p23c-ws-bad-");

        for (String bad : new String[]{"yesterday", "2026-13-45T99:99:99Z", "05-01-2026"}) {
            MvcResult result = call(user, post("/api/v1/workout-sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("title", "Bad stamp", "started_at", bad))));

            assertStructuredError(result, 400, "Bad Request");
            assertThat(json(result).path("message").asText()).contains("started_at");
        }
    }

    @Test
    @DisplayName("Phase 23C workout sessions - the composite endpoint still stamps started_at itself")
    void compositeEndpointIsUnchanged() throws Exception {
        Session user = register("p23c-ws-composite-");
        // The exercises catalogue is not seeded by the migrations, so this test inserts the one row
        // it needs rather than depending on catalog content another test may have created.
        UUID exerciseId = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name,muscle_group,equipment,difficulty,is_compound) values (?,?,?,?,?,?)",
                exerciseId, "Bench press", "Chest", "Barbell", "Intermediate", true);

        Instant before = Instant.now().minusSeconds(30);
        MvcResult result = call(user, post("/api/v1/workout-sessions/complete")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "session", Map.of("title", "Composite", "workout_type", "Strength",
                                "session_date", LocalDate.now(ZoneOffset.UTC).toString(),
                                "duration_minutes", 30, "completed", true),
                        "exercises", java.util.List.of(Map.of("exercise_id", exerciseId, "order_index", 0,
                                "sets", java.util.List.of(Map.of("set_number", 1, "reps", 8, "completed", true))))))));

        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString()).isIn(200, 201);
        UUID id = UUID.fromString(json(result).path("id").asText());
        // started_at is deliberately server-generated on this path, so it must be a recent instant
        // and not anything supplied by the request. Phase 23C changed the binder only, so this
        // guards against the two paths drifting into each other.
        Instant started = jdbc.queryForObject("select started_at from workout_sessions where id=?", Timestamp.class, id)
                .toInstant();
        assertThat(started).isAfterOrEqualTo(before);
        assertThat(started).isBefore(Instant.now().plusSeconds(30));
    }
}
