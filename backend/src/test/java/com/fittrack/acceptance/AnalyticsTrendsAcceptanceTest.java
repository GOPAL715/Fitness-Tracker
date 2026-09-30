package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 21: analytics timezone resolution, trend bucketing, fiber, and workout-source consistency.
 *
 * <p>These assertions read the real PostgreSQL container rather than a stub, because most of what is
 * under test is a database contract: {@code date_trunc} bucket boundaries, the inclusive range rule,
 * and the canonical views' one-row-per-day guarantee.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class AnalyticsTrendsAcceptanceTest extends AbstractAcceptanceTest {

    private JsonNode trends(Session user, String query) throws Exception {
        return json(getAs(user, "/api/v1/analytics/trends" + query));
    }

    /** Records the caller's IANA zone exactly as the health timezone endpoint does. */
    private void setZone(Session user, String zone) {
        jdbc.update("UPDATE app_users SET timezone=? WHERE id=CAST(? AS uuid)", zone, user.id());
    }

    private void metric(String userId, LocalDate date, int steps) {
        jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps,sleep_hours,calories_burned)"
                        + " values (?,?::uuid,?,?,?,?)",
                UUID.randomUUID(), userId, date, steps, new BigDecimal("7.5"), 2000);
    }

    private void meal(String userId, LocalDate date, String calories, String fiber) {
        jdbc.update("insert into meals(id,user_id,meal_date,meal_type,name,source,calories,protein_g,"
                        + "carbs_g,fat_g,fiber_g) values (?,?::uuid,?,'LUNCH','Trend meal','manual',?,10,20,5,?)",
                UUID.randomUUID(), userId, date, new BigDecimal(calories), new BigDecimal(fiber));
    }

    private void quickLog(String userId, LocalDate date, int minutes, int calories) {
        jdbc.update("insert into workouts(id,user_id,title,duration_minutes,calories_burned,workout_date,"
                        + "completed) values (?,?::uuid,'Quick log',?,?,?,true)",
                UUID.randomUUID(), userId, minutes, calories, date);
    }


    // ------------------------------------------------------------- timezone

    @Nested
    @DisplayName("Phase 21 - analytics timezone resolution")
    class TimezoneResolution {

        @Test
        @DisplayName("a stored zone is used and reported as resolved")
        void storedZoneIsUsedAndReported() throws Exception {
            Session user = register("tz-set-");
            setZone(user, "Asia/Kolkata");

            JsonNode dashboard = json(getAs(user, "/api/v1/analytics/dashboard"));
            assertThat(dashboard.path("timezone").asText()).isEqualTo("Asia/Kolkata");
            assertThat(dashboard.path("timezone_resolved").asBoolean())
                    .as("the caller's own zone is the resolved one").isTrue();
            assertThat(trends(user, "?bucket=day").path("timezone_resolved").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("an unset zone falls back to UTC and says so")
        void missingZoneFallsBackToUtcAndSaysSo() throws Exception {
            Session user = register("tz-none-");
            // V11 never backfills this column, so a brand-new account genuinely has no zone.
            assertThat(jdbc.queryForObject("select timezone from app_users where id=CAST(? as uuid)",
                    String.class, user.id())).as("no zone is invented").isNull();

            JsonNode dashboard = json(getAs(user, "/api/v1/analytics/dashboard"));
            assertThat(dashboard.path("timezone").asText()).isEqualTo("UTC");
            assertThat(dashboard.path("timezone_resolved").asBoolean())
                    .as("a fallback is not the same as a resolved zone").isFalse();
        }

        @Test
        @DisplayName("the fallback answers the request rather than rejecting it")
        void fallbackDoesNotRejectTheRequest() throws Exception {
            Session user = register("tz-fallback-ok-");
            assertStatus(getAs(user, "/api/v1/analytics/dashboard"), 200);
            assertStatus(getAs(user, "/api/v1/analytics/trends?bucket=month"), 200);
        }

        @Test
        @DisplayName("an unusable stored zone is treated as unset, not trusted")
        void unresolvableStoredZoneFallsBack() throws Exception {
            Session user = register("tz-bad-");
            // Defence in depth: health ingest validates before writing, so this state should not
            // arise. If it ever does, analytics must not hand the string to java.time and crash.
            jdbc.update("UPDATE app_users SET timezone='Mars/Olympus' WHERE id=CAST(? AS uuid)", user.id());

            JsonNode dashboard = json(getAs(user, "/api/v1/analytics/dashboard"));
            assertThat(dashboard.path("timezone").asText()).isEqualTo("UTC");
            assertThat(dashboard.path("timezone_resolved").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("analytics does not borrow the notification or reminder zone")
        void unrelatedTimezonesAreNotBorrowed() throws Exception {
            Session user = register("tz-not-borrowed-");
            // Both of these belong to other features and are set deliberately.
            jdbc.update("insert into user_notification_preferences(user_id,timezone,quiet_hours_enabled,"
                            + "quiet_hours_start,quiet_hours_end) "
                            + "values (?::uuid,'Pacific/Auckland',false,null,null)", user.id());
            jdbc.update("insert into reminders(id,user_id,type,title,scheduled_time,days_of_week,"
                            + "enabled,timezone) values (?,?::uuid,'DAILY','R','08:00','1,2,3,4,5,6,7',"
                            + "true,'America/Denver')", UUID.randomUUID(), user.id());

            JsonNode dashboard = json(getAs(user, "/api/v1/analytics/dashboard"));
            assertThat(dashboard.path("timezone").asText()).isEqualTo("UTC");
            assertThat(dashboard.path("timezone_resolved").asBoolean())
                    .as("a quiet-hours zone is not the user's calendar").isFalse();
        }
    }

    // -------------------------------------------------------------- bucketing

    @Nested
    @DisplayName("Phase 21 - trend buckets")
    class Buckets {

        @Test
        @DisplayName("a day bucket is the date itself")
        void dayBucketIsTheDate() throws Exception {
            Session user = register("tr-day-");
            metric(user.id(), LocalDate.of(2026, 3, 10), 1000);
            metric(user.id(), LocalDate.of(2026, 3, 11), 2000);

            JsonNode body = trends(user, "?from=2026-03-10&to=2026-03-11&bucket=day");
            assertThat(body.path("bucket").asText()).isEqualTo("day");
            assertThat(body.path("buckets")).hasSize(2);
            assertThat(body.path("buckets").get(0).path("start").asText()).isEqualTo("2026-03-10");
            assertThat(body.path("buckets").get(0).path("end").asText()).isEqualTo("2026-03-10");
            assertThat(body.path("buckets").get(0).path("steps").asLong()).isEqualTo(1000);
            assertThat(body.path("buckets").get(1).path("steps").asLong()).isEqualTo(2000);
        }

        @Test
        @DisplayName("a week bucket starts on Monday and sums the days it covers")
        void weekBucketStartsMondayAndSums() throws Exception {
            Session user = register("tr-week-");
            // 2026-03-09 is a Monday. Wednesday and Friday share the week beginning that Monday.
            metric(user.id(), LocalDate.of(2026, 3, 11), 1000);
            metric(user.id(), LocalDate.of(2026, 3, 13), 2500);
            metric(user.id(), LocalDate.of(2026, 3, 16), 700); // the following Monday

            JsonNode body = trends(user, "?from=2026-03-09&to=2026-03-17&bucket=week");
            assertThat(body.path("buckets")).hasSize(2);
            JsonNode first = body.path("buckets").get(0);
            assertThat(first.path("start").asText()).as("ISO weeks start Monday").isEqualTo("2026-03-09");
            assertThat(first.path("end").asText()).isEqualTo("2026-03-15");
            assertThat(first.path("steps").asLong()).as("a week sums its days").isEqualTo(3500);
            assertThat(first.path("days").asLong()).isEqualTo(7);

            JsonNode second = body.path("buckets").get(1);
            assertThat(second.path("start").asText()).isEqualTo("2026-03-16");
            assertThat(second.path("steps").asLong()).isEqualTo(700);
        }

        @Test
        @DisplayName("a month bucket starts on the first and spans the whole month")
        void monthBucketSpansTheCalendarMonth() throws Exception {
            Session user = register("tr-month-");
            metric(user.id(), LocalDate.of(2026, 1, 15), 300);
            metric(user.id(), LocalDate.of(2026, 2, 3), 400);
            metric(user.id(), LocalDate.of(2026, 2, 28), 600);

            JsonNode body = trends(user, "?from=2026-01-01&to=2026-02-28&bucket=month");
            assertThat(body.path("buckets")).hasSize(2);
            assertThat(body.path("buckets").get(0).path("start").asText()).isEqualTo("2026-01-01");
            assertThat(body.path("buckets").get(0).path("steps").asLong()).isEqualTo(300);

            JsonNode february = body.path("buckets").get(1);
            assertThat(february.path("start").asText()).isEqualTo("2026-02-01");
            assertThat(february.path("end").asText()).isEqualTo("2026-02-28");
            assertThat(february.path("steps").asLong()).isEqualTo(1000);
        }

        @Test
        @DisplayName("a partial bucket reports how many days the range covered")
        void partialBucketIsLabelledNotTruncated() throws Exception {
            Session user = register("tr-partial-");
            // One Wednesday. Its week runs Monday to Sunday; the range spans only three of those.
            metric(user.id(), LocalDate.of(2026, 3, 11), 5000);

            JsonNode bucket = trends(user, "?from=2026-03-11&to=2026-03-13&bucket=week")
                    .path("buckets").get(0);
            assertThat(bucket.path("start").asText())
                    .as("the bucket keeps its own start, which precedes the range").isEqualTo("2026-03-09");
            assertThat(bucket.path("end").asText()).isEqualTo("2026-03-15");
            assertThat(bucket.path("days").asLong())
                    .as("three of the week's days were in range, so it is not a full-week total")
                    .isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Phase 21 - empty, null and merged series")
    class SeriesSemantics {

        @Test
        @DisplayName("buckets with no data are absent rather than zeroed")
        void emptyBucketsAreOmitted() throws Exception {
            Session user = register("tr-empty-");
            metric(user.id(), LocalDate.of(2026, 3, 2), 1000);
            metric(user.id(), LocalDate.of(2026, 3, 16), 1000);

            JsonNode body = trends(user, "?from=2026-03-02&to=2026-03-16&bucket=week");
            assertThat(body.path("buckets")).as("only the weeks holding data").hasSize(2);
            for (JsonNode bucket : body.path("buckets")) {
                assertThat(bucket.path("start").asText()).isIn("2026-03-02", "2026-03-16");
            }
        }

        @Test
        @DisplayName("a recorded zero stays zero while an absent measure stays null")
        void recordedZeroAndAbsentMeasureDiffer() throws Exception {
            Session user = register("tr-null-");
            // A measured zero: the user recorded that they took no steps. That is a real fact.
            jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps) values (?,?::uuid,?,0)",
                    UUID.randomUUID(), user.id(), LocalDate.of(2026, 3, 10));

            JsonNode body = trends(user, "?from=2026-03-10&to=2026-03-10&bucket=day");
            assertThat(body.path("buckets")).hasSize(1);
            assertThat(body.path("buckets").get(0).path("steps").asLong())
                    .as("a recorded zero is reported as zero").isZero();
            assertThat(body.path("buckets").get(0).path("active_minutes").isNull())
                    .as("an unrecorded measure is null, not 0").isTrue();
        }

        @Test
        @DisplayName("an empty account returns an empty series, not an error")
        void emptyAccountReturnsEmptySeries() throws Exception {
            Session user = register("tr-none-");
            for (String bucket : new String[]{"day", "week", "month"}) {
                JsonNode body = trends(user, "?from=2026-01-01&to=2026-01-31&bucket=" + bucket);
                assertThat(body.path("buckets")).as("empty %s series", bucket).isEmpty();
                assertThat(body.path("bucket").asText()).isEqualTo(bucket);
            }
        }

        @Test
        @DisplayName("activity, nutrition and workouts land in the same bucket")
        void allSeriesShareTheSameBuckets() throws Exception {
            Session user = register("tr-merge-");
            LocalDate day = LocalDate.of(2026, 3, 11);
            metric(user.id(), day, 4000);
            meal(user.id(), day, "2200", "30");
            quickLog(user.id(), day, 45, 400);

            JsonNode body = trends(user, "?from=2026-03-09&to=2026-03-15&bucket=week");
            assertThat(body.path("buckets")).hasSize(1);
            JsonNode bucket = body.path("buckets").get(0);
            assertThat(bucket.path("steps").asLong()).isEqualTo(4000);
            assertThat(bucket.path("nutrition_calories").asLong()).isEqualTo(2200);
            assertThat(bucket.path("fiber_g").asDouble()).isEqualTo(30.0);
            assertThat(bucket.path("workouts").asLong()).isEqualTo(1);
            assertThat(bucket.path("workout_minutes").asLong()).isEqualTo(45);
        }

        @Test
        @DisplayName("an unknown bucket is rejected rather than silently defaulted")
        void unknownBucketIsRejected() throws Exception {
            Session user = register("tr-badbucket-");
            for (String bad : new String[]{"year", "hour", "DAY!"}) {
                MvcResult result = call(user, get("/api/v1/analytics/trends?bucket=" + bad));
                assertStructuredError(result, 400, "Bad Request");
            }
        }
    }

    // --------------------------------------------- workout source consistency

    @Nested
    @DisplayName("Phase 21 - one consistent training-activity semantic")
    class WorkoutSource {

        @Test
        @DisplayName("workouts and calendar agree on the activity count for the same day")
        void workoutsAndCalendarAgree() throws Exception {
            Session user = register("ws-agree-");
            LocalDate day = LocalDate.of(2026, 4, 8);
            quickLog(user.id(), day, 30, 300);
            loggedSession(user.id(), day, 45);

            JsonNode series = json(getAs(user,
                    "/api/v1/analytics/workouts?from=2026-04-08&to=2026-04-08"));
            assertThat(series).hasSize(1);
            assertThat(series.get(0).path("sessions").asLong())
                    .as("a quick log and a logged session are both training activity").isEqualTo(2);

            JsonNode calendar = json(getAs(user,
                    "/api/v1/analytics/calendar?from=2026-04-08&to=2026-04-08"));
            assertThat(calendar.get(0).path("summary").path("workouts").asLong())
                    .as("the calendar must not report a different number for the same day")
                    .isEqualTo(series.get(0).path("sessions").asLong());
        }

        @Test
        @DisplayName("the two record types stay distinguishable while summing to the total")
        void conceptsArePreservedNotMerged() throws Exception {
            Session user = register("ws-split-");
            LocalDate day = LocalDate.of(2026, 4, 9);
            quickLog(user.id(), day, 30, 300);
            loggedSession(user.id(), day, 45);

            JsonNode point = json(getAs(user,
                    "/api/v1/analytics/workouts?from=2026-04-09&to=2026-04-09")).get(0);
            assertThat(point.path("quick_logged").asLong()).isEqualTo(1);
            assertThat(point.path("logged_sessions").asLong()).isEqualTo(1);
            assertThat(point.path("sessions").asLong()).isEqualTo(2);
            assertThat(point.path("minutes").asLong()).as("durations from both").isEqualTo(75);
        }

        @Test
        @DisplayName("a logged session has no recorded calories, so the total is unknown not zero")
        void loggedSessionCaloriesAreUnknownNotZero() throws Exception {
            Session user = register("ws-cal-");
            loggedSession(user.id(), LocalDate.of(2026, 4, 10), 45);

            JsonNode point = json(getAs(user,
                    "/api/v1/analytics/workouts?from=2026-04-10&to=2026-04-10")).get(0);
            assertThat(point.path("calories").isNull())
                    .as("workout_sessions has no calorie column, so a zero would be a claim").isTrue();
        }

        @Test
        @DisplayName("quick-log calories are reported when every activity recorded one")
        void quickLogCaloriesAreKnown() throws Exception {
            Session user = register("ws-cal-known-");
            quickLog(user.id(), LocalDate.of(2026, 4, 11), 30, 300);

            JsonNode point = json(getAs(user,
                    "/api/v1/analytics/workouts?from=2026-04-11&to=2026-04-11")).get(0);
            assertThat(point.path("calories").asLong()).isEqualTo(300);
        }
    }

    // --------------------------------------------- fiber and calorie semantics

    @Nested
    @DisplayName("Phase 21 - fiber and calorie semantics")
    class Measures {

        @Test
        @DisplayName("fiber aggregates on the same footing as the other macros")
        void fiberAggregatesWithTheOtherMacros() throws Exception {
            Session user = register("fiber-agg-");
            LocalDate day = LocalDate.of(2026, 6, 1);
            meal(user.id(), day, "500", "8");
            meal(user.id(), day, "400", "7");

            JsonNode body = json(getAs(user, "/api/v1/analytics/nutrition?from=2026-06-01&to=2026-06-01"));
            assertThat(body.path("daily").get(0).path("calories").asDouble()).isEqualTo(900);
            assertThat(body.path("daily").get(0).path("fiber_g").asDouble()).isEqualTo(15.0);
            assertThat(body.path("totals").path("fiber_g").asDouble()).isEqualTo(15.0);
        }

        @Test
        @DisplayName("a meal with no fiber contributes calories without inventing fiber")
        void nullFiberIsSkippedNotTreatedAsZero() throws Exception {
            Session user = register("fiber-null-");
            jdbc.update("insert into meals(id,user_id,meal_date,meal_type,name,source,calories,protein_g,"
                            + "carbs_g,fat_g) values (?,?::uuid,'2026-06-02','LUNCH','Old row','manual',"
                            + "600,10,20,5)", UUID.randomUUID(), user.id());
            meal(user.id(), LocalDate.of(2026, 6, 2), "300", "9");

            JsonNode totals = json(getAs(user,
                    "/api/v1/analytics/nutrition?from=2026-06-02&to=2026-06-02")).path("totals");
            assertThat(totals.path("calories").asDouble()).isEqualTo(900);
            assertThat(totals.path("fiber_g").asDouble())
                    .as("only the row that recorded fiber contributes").isEqualTo(9.0);
        }

        @Test
        @DisplayName("no unified calorie total is invented across the two calorie sources")
        void caloriesAreNotUnified() throws Exception {
            Session user = register("cal-separate-");
            LocalDate day = LocalDate.of(2026, 6, 3);
            // A device's whole-day estimate and a user-typed per-session figure.
            metric(user.id(), day, 4000);
            quickLog(user.id(), day, 30, 300);

            JsonNode bucket = trends(user, "?from=2026-06-03&to=2026-06-03").path("buckets").get(0);
            assertThat(bucket.path("activity_calories_burned").asLong())
                    .as("the device estimate keeps its own field").isEqualTo(2000);
            assertThat(bucket.path("workout_calories").asLong())
                    .as("the typed figure keeps its own field").isEqualTo(300);
            // No combined field is published at all. `isMissingNode` rather than `isNull`: a field
            // the response never declares is an absent node, not a JSON null.
            assertThat(bucket.has("total_calories"))
                    .as("the two must never be summed into one invented number").isFalse();
            assertThat(bucket.has("calories_burned_total"))
                    .as("and not under another name").isFalse();
        }
    }

    // ------------------------------------------------------------ range limits

    @Nested
    @DisplayName("Phase 21 - one shared range contract")
    class RangeLimits {

        @Test
        @DisplayName("trends and calendar enforce the same inclusive bound")
        void trendsAndCalendarShareTheBound() throws Exception {
            Session user = register("range-same-");
            String maximum = "from=2025-01-01&to=2026-01-01"; // 366 inclusive days
            String beyond = "from=2025-01-01&to=2026-01-02";   // one day too many

            assertStatus(getAs(user, "/api/v1/analytics/trends?" + maximum), 200);
            assertStatus(getAs(user, "/api/v1/calendar/summary?" + maximum), 200);

            MvcResult trendsBeyond = getAs(user, "/api/v1/analytics/trends?" + beyond);
            MvcResult calendarBeyond = getAs(user, "/api/v1/calendar/summary?" + beyond);
            assertStructuredError(trendsBeyond, 400, "Bad Request");
            assertStructuredError(calendarBeyond, 400, "Bad Request");

            assertThat(json(trendsBeyond).path("message").asText())
                    .as("both surfaces state the same limit").contains("must not exceed 366 days");
            assertThat(json(calendarBeyond).path("message").asText())
                    .contains("must not exceed 366 days");
        }
    }

    @Test
    @DisplayName("an uncompleted record is not training activity")
    void uncompletedRecordsAreIgnored() throws Exception {
        Session user = register("ws-incomplete-");
        jdbc.update("insert into workouts(id,user_id,title,workout_date,completed)"
                + " values (?,?::uuid,'Planned','2026-04-12',false)", UUID.randomUUID(), user.id());
        jdbc.update("insert into workout_sessions(id,user_id,title,session_date,completed)"
                + " values (?,?::uuid,'Planned','2026-04-12',false)", UUID.randomUUID(), user.id());

        assertThat(json(getAs(user, "/api/v1/analytics/workouts?from=2026-04-12&to=2026-04-12")))
                .as("a plan is not a performed session").isEmpty();
    }

    @Test
    @DisplayName("a month bucket clipped by the range reports the days it covered")
    void partialMonthBucketIsLabelled() throws Exception {
        Session user = register("tr-partialmonth-");
        metric(user.id(), LocalDate.of(2026, 5, 20), 900);

        JsonNode bucket = trends(user, "?from=2026-05-20&to=2026-05-25&bucket=month")
                .path("buckets").get(0);
        assertThat(bucket.path("start").asText()).isEqualTo("2026-05-01");
        assertThat(bucket.path("end").asText()).isEqualTo("2026-05-31");
        assertThat(bucket.path("days").asLong()).isEqualTo(6);
    }

    @Test
    @DisplayName("an empty bucket parameter falls back to day")
    void emptyBucketFallsBackToDay() throws Exception {
        Session user = register("tr-emptybucket-");
        assertThat(trends(user, "?bucket=").path("bucket").asText()).isEqualTo("day");
    }

    @Test
    @DisplayName("changing the zone changes today, not the stored days")
    void zoneChangeAffectsTodayNotHistory() throws Exception {
        Session user = register("tz-today-");
        // Two real local days either side of the UTC date line.
        metric(user.id(), LocalDate.of(2026, 3, 10), 1000);
        metric(user.id(), LocalDate.of(2026, 3, 11), 2000);

        setZone(user, "Pacific/Kiritimati"); // UTC+14, so it is already tomorrow there
        JsonNode series = trends(user, "?from=2026-03-10&to=2026-03-11&bucket=day");
        assertThat(series.path("buckets")).hasSize(2);
        assertThat(series.path("buckets").get(1).path("steps").asLong())
                .as("stored days are calendar days and are not re-zoned").isEqualTo(2000);
    }

    @Test
    @DisplayName("an inverted or malformed range is a structured 400")
    void invalidRangesAreRejected() throws Exception {
        Session user = register("range-bad-");
        assertStructuredError(getAs(user, "/api/v1/analytics/trends?from=2026-03-10&to=2026-03-01"),
                400, "Bad Request");
        assertStructuredError(getAs(user, "/api/v1/analytics/trends?from=not-a-date"),
                400, "Bad Request");
    }

    @Test
    @DisplayName("trends require authentication")
    void trendsRequireAuthentication() throws Exception {
        assertUnauthenticated(get("/api/v1/analytics/trends"));
        assertUnauthenticated(get("/api/v1/analytics/trends?bucket=week"));
    }

    @Test
    @DisplayName("no workout volume is exposed anywhere in analytics")
    void noWorkoutVolumeIsExposed() throws Exception {
        Session user = register("no-volume-");
        LocalDate day = LocalDate.of(2026, 8, 1);
        quickLog(user.id(), day, 45, 400);
        loggedSession(user.id(), day, 50);
        metric(user.id(), day, 5000);

        // exercise_sets.weight_unit is unnormalized, so a summed volume could be wrong for anyone
        // who logs in kilograms. Phase 21 deliberately publishes no such figure.
        for (String path : new String[]{
                "/api/v1/analytics/workouts?from=2026-08-01&to=2026-08-01",
                "/api/v1/analytics/trends?from=2026-08-01&to=2026-08-01",
                "/api/v1/analytics/dashboard",
                "/api/v1/analytics/weekly"}) {
            String body = getAs(user, path).getResponse().getContentAsString();
            assertThat(body.toLowerCase(java.util.Locale.ROOT))
                    .as("no volume field in %s", path).doesNotContain("volume");
        }
    }

    /** A structured session, which has no calorie column at all. */
    private void loggedSession(String userId, LocalDate date, int minutes) {
        jdbc.update("insert into workout_sessions(id,user_id,title,session_date,duration_minutes,completed)"
                        + " values (?,?::uuid,'Logged session',?,?,true)",
                UUID.randomUUID(), userId, date, minutes);
    }
}
