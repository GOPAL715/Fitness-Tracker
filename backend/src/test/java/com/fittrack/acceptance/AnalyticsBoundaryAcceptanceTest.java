package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 14 item 11: analytics range boundary behaviour for the real current endpoints.
 *
 * <p>Range endpoints accept optional {@code from}/{@code to}; {@code /dashboard} and
 * {@code /ai-usage} take no dates at all and are tested for that contract instead of being
 * forced into a range they do not support.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class AnalyticsBoundaryAcceptanceTest extends AbstractAcceptanceTest {

    /** Endpoints that accept an optional from/to range and their default window in days. */
    private static final String[][] RANGED = {
            {"workouts", "90"}, {"nutrition", "90"}, {"progress", "90"},
            {"weekly", "7"}, {"activity", "90"}, {"body", "90"},
            {"habits", "7"}, {"calendar", "31"}
    };

    private void metric(Session user, LocalDate date, int steps) {
        jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps,sleep_hours,calories_burned,water_oz,active_minutes)"
                + " values (?,?::uuid,?,?,?,?,?,?)",
                UUID.randomUUID(), user.id(), date, steps, 7.5, 200, 50, 30);
    }

    /** Asserts a calendar day carries no activity, as expected for an inactive day. */
    private void assertZeroedCalendarDay(JsonNode day) {
        assertThat(day.path("date").asText()).isNotBlank();
        JsonNode summary = day.path("summary");
        assertThat(summary.path("steps").asInt()).isZero();
        assertThat(summary.path("water_oz").asInt()).isZero();
        assertThat(summary.path("calories_burned").asInt()).isZero();
        assertThat(summary.path("active_minutes").asInt()).isZero();
        assertThat(summary.path("sleep_hours").asInt()).isZero();
        assertThat(summary.path("workouts").asInt()).isZero();
        assertThat(summary.path("meals").asInt()).isZero();
        assertThat(summary.path("habits_completed").asInt()).isZero();
    }

    // ------------------------------------------------------- empty dataset

    @Test
    @DisplayName("Phase 14 item 11 - an empty dataset returns empty series, not errors")
    void emptyDatasetReturnsEmptySeries() throws Exception {
        Session user = register("an-empty-");
        LocalDate today = LocalDate.now();

        for (String[] spec : RANGED) {
            MvcResult result = getAs(user, "/api/v1/analytics/" + spec[0]);
            assertStatus(result, 200);
            JsonNode body = json(result);
            if ("calendar".equals(spec[0])) {
                // The calendar is a dense day list by design: one entry per day in the window,
                // even when the user recorded nothing. Only the values may be zero.
                assertThat(body).as("calendar covers the whole default window").hasSize(31);
                assertThat(body.get(0).path("date").asText())
                        .isEqualTo(today.minusDays(30).toString());
                assertThat(body.get(30).path("date").asText()).isEqualTo(today.toString());
                body.forEach(day -> assertZeroedCalendarDay(day));
            } else if (body.isArray()) {
                assertThat(body).as("%s on an empty dataset", spec[0]).isEmpty();
            } else {
                assertThat(body.path("daily")).as("%s daily", spec[0]).isEmpty();
                assertThat(body.path("activity")).as("%s activity", spec[0]).isEmpty();
                assertThat(body.path("nutrition")).as("%s nutrition", spec[0]).isEmpty();
                assertThat(body.path("workouts")).as("%s workouts", spec[0]).isEmpty();
                assertThat(body.path("body")).as("%s body", spec[0]).isEmpty();
            }
        }

        JsonNode dashboard = json(getAs(user, "/api/v1/analytics/dashboard"));
        assertThat(dashboard.path("activity").path("steps").asInt()).isZero();
        assertThat(dashboard.path("nutrition").path("calories").asInt()).isZero();
        assertThat(dashboard.path("workout").path("sessions").asInt()).isZero();
    }

    // --------------------------------------------------- single data point

    @Test
    @DisplayName("Phase 14 item 11 - a single data point is returned for a same-day range")
    void singleDataPointIsReturned() throws Exception {
        Session user = register("an-single-");
        LocalDate day = LocalDate.of(2026, 3, 10);
        metric(user, day, 4242);

        MvcResult activity = getAs(user, "/api/v1/analytics/activity?from=2026-03-10&to=2026-03-10");
        assertStatus(activity, 200);
        JsonNode body = json(activity);
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("date").asText()).isEqualTo("2026-03-10");
        assertThat(body.get(0).path("steps").asInt()).isEqualTo(4242);
    }

    // --------------------------------------------------------- same day range

    @Test
    @DisplayName("Phase 14 item 11 - a same-day range includes only that day")
    void sameDayRangeIncludesOnlyThatDay() throws Exception {
        Session user = register("an-sameday-");
        metric(user, LocalDate.of(2026, 3, 10), 111);
        metric(user, LocalDate.of(2026, 3, 11), 222);
        metric(user, LocalDate.of(2026, 3, 12), 333);

        JsonNode body = json(getAs(user, "/api/v1/analytics/activity?from=2026-03-11&to=2026-03-11"));
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("steps").asInt()).isEqualTo(222);
    }

    // -------------------------------------------------------- multi day range

    @Test
    @DisplayName("Phase 14 item 11 - a multi-day range returns each day in order")
    void multiDayRangeReturnsEveryDayInOrder() throws Exception {
        Session user = register("an-multiday-");
        metric(user, LocalDate.of(2026, 3, 10), 10);
        metric(user, LocalDate.of(2026, 3, 11), 20);
        metric(user, LocalDate.of(2026, 3, 12), 30);

        JsonNode body = json(getAs(user, "/api/v1/analytics/activity?from=2026-03-10&to=2026-03-12"));
        assertThat(body).hasSize(3);
        assertThat(body.get(0).path("date").asText()).isEqualTo("2026-03-10");
        assertThat(body.get(1).path("date").asText()).isEqualTo("2026-03-11");
        assertThat(body.get(2).path("date").asText()).isEqualTo("2026-03-12");
        assertThat(body.get(2).path("steps").asInt()).isEqualTo(30);
    }

    // ------------------------------------------------------- future only range

    @Test
    @DisplayName("Phase 14 item 11 - a future-only range is valid and returns no data")
    void futureOnlyRangeReturnsNoData() throws Exception {
        Session user = register("an-future-");
        LocalDate today = LocalDate.now();
        metric(user, today, 777);

        JsonNode body = json(getAs(user,
                "/api/v1/analytics/activity?from=" + today.plusDays(10) + "&to=" + today.plusDays(20)));
        assertThat(body).isEmpty();

        JsonNode nutrition = json(getAs(user,
                "/api/v1/analytics/nutrition?from=" + today.plusDays(10) + "&to=" + today.plusDays(20)));
        assertThat(nutrition.path("daily")).isEmpty();
        assertThat(nutrition.path("totals").path("calories").asInt()).isZero();
        assertThat(nutrition.path("from").asText()).isEqualTo(today.plusDays(10).toString());
    }

    // ------------------------------------------------------- invalid ranges

    @Test
    @DisplayName("Phase 14 item 11 - an inverted range is rejected as a structured 400")
    void invertedRangeIsRejected() throws Exception {
        Session user = register("an-inverted-");
        for (String[] spec : RANGED) {
            MvcResult result = call(user, get("/api/v1/analytics/" + spec[0] + "?from=2026-03-10&to=2026-03-01"));
            assertStructuredError(result, 400, "Bad Request");
            assertThat(json(result).path("message").asText()).contains("from must not be after to");
        }
    }

    // ---------------------------------------------------- 366 day boundary

    @Test
    @DisplayName("Phase 14 item 11 - exactly 366 days is accepted, 367 is rejected")
    void maximumAllowedRangeIsAcceptedAndBeyondIsRejected() throws Exception {
        Session user = register("an-366-");
        // 2025-01-01 .. 2026-01-01 is 365 days apart, i.e. 366 inclusive days.
        String maximum = "?from=2025-01-01&to=2026-01-01";
        // 2025-01-01 .. 2026-01-02 is 366 days apart, one day beyond the limit.
        String beyond = "?from=2025-01-01&to=2026-01-02";

        for (String[] spec : RANGED) {
            MvcResult accepted = call(user, get("/api/v1/analytics/" + spec[0] + maximum));
            assertStatus(accepted, 200);

            MvcResult rejected = call(user, get("/api/v1/analytics/" + spec[0] + beyond));
            assertStructuredError(rejected, 400, "Bad Request");
            assertThat(json(rejected).path("message").asText()).contains("must not exceed 366 days");
        }
    }

    @Test
    @DisplayName("Phase 14 item 11 - a single-day boundary range is inside the limit")
    void singleDayBoundaryRangeIsAccepted() throws Exception {
        Session user = register("an-boundary-single-");
        assertStatus(call(user, get("/api/v1/analytics/activity?from=2026-01-01&to=2026-01-01")), 200);
        assertStatus(call(user, get("/api/v1/analytics/activity?from=2026-01-01&to=2026-01-02")), 200);
    }

    @Test
    @DisplayName("Phase 14 item 11 - the calendar is a dense day list across the requested range")
    void calendarReturnsOneEntryPerDayInTheRequestedRange() throws Exception {
        Session user = register("an-calendar-dense-");
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 7);
        // Activity exists on only two of the seven days.
        metric(user, LocalDate.of(2026, 9, 3), 3333);
        metric(user, LocalDate.of(2026, 9, 6), 6666);

        JsonNode body = json(getAs(user, "/api/v1/analytics/calendar?from=2026-09-01&to=2026-09-07"));

        // Dense coverage: every day in the bounded range is present, in ascending order.
        assertThat(body).as("one entry per day in the range").hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(body.get(i).path("date").asText())
                    .as("day %d is %s", i, from.plusDays(i))
                    .isEqualTo(from.plusDays(i).toString());
        }

        // Active days carry the caller's own values.
        assertThat(body.get(2).path("summary").path("steps").asInt()).isEqualTo(3333);
        assertThat(body.get(5).path("summary").path("steps").asInt()).isEqualTo(6666);

        // Inactive days are present and zeroed rather than omitted.
        for (int i : new int[] {0, 1, 3, 4, 6}) assertZeroedCalendarDay(body.get(i));
    }

    @Test
    @DisplayName("Phase 14 item 11 - a dense calendar never leaks another user's activity")
    void calendarDensityDoesNotHideACrossUserLeak() throws Exception {
        Session owner = register("an-cal-owner-");
        Session other = register("an-cal-other-");
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 5);
        metric(owner, LocalDate.of(2026, 4, 2), 4444);
        metric(other, LocalDate.of(2026, 4, 3), 8888);

        JsonNode ownerBody = json(getAs(owner,
                "/api/v1/analytics/calendar?from=" + from + "&to=" + to));
        assertThat(ownerBody).as("still dense").hasSize(5);
        assertThat(ownerBody.get(1).path("summary").path("steps").asInt())
                .as("A sees A's activity").isEqualTo(4444);
        assertThat(ownerBody.get(2).path("summary").path("steps").asInt())
                .as("B's day is present but zeroed for A").isZero();
        assertThat(ownerBody.toString()).as("B's value never appears in A's calendar")
                .doesNotContain("8888");

        JsonNode otherBody = json(getAs(other,
                "/api/v1/analytics/calendar?from=" + from + "&to=" + to));
        assertThat(otherBody).hasSize(5);
        assertThat(otherBody.get(2).path("summary").path("steps").asInt()).isEqualTo(8888);
        assertThat(otherBody.get(1).path("summary").path("steps").asInt()).isZero();
        assertThat(otherBody.toString()).doesNotContain("4444");
    }

    // ------------------------------------------- missing date parameter semantics

    @Test
    @DisplayName("Phase 14 item 11 - omitted dates fall back to each endpoint's default window")
    void missingDatesUseEndpointDefaults() throws Exception {
        Session user = register("an-defaults-");
        LocalDate today = LocalDate.now();

        for (String[] spec : RANGED) {
            MvcResult result = getAs(user, "/api/v1/analytics/" + spec[0]);
            assertStatus(result, 200);
            JsonNode body = json(result);
            if (body.isArray()) {
                // Array endpoints expose no range, so assert the shape only.
                assertThat(body.isArray()).isTrue();
                continue;
            }
            assertThat(body.path("to").asText())
                    .as("%s default window end", spec[0]).isEqualTo(today.toString());
            assertThat(body.path("from").asText())
                    .as("%s default window start", spec[0])
                    .isEqualTo(today.minusDays(Long.parseLong(spec[1]) - 1).toString());
        }
    }

    @Test
    @DisplayName("Phase 14 item 11 - a single supplied date is treated as a start bound")
    void onlyFromIsHonoured() throws Exception {
        Session user = register("an-onlyfrom-");
        LocalDate today = LocalDate.now();
        JsonNode body = json(getAs(user, "/api/v1/analytics/activity?from=" + today));
        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("Phase 14 item 11 - endpoints without date parameters ignore supplied dates")
    void endpointsWithoutDatesIgnoreThem() throws Exception {
        Session user = register("an-nodates-");
        // /dashboard and /ai-usage take no dates; extra parameters must not break them.
        assertStatus(call(user, get("/api/v1/analytics/dashboard?from=2020-01-01&to=2020-01-02")), 200);
        assertStatus(call(user, get("/api/v1/analytics/ai-usage?from=2020-01-01&to=2020-01-02")), 200);
    }

    @Test
    @DisplayName("Phase 14 item 11 - an unparseable date is rejected as a structured 400")
    void malformedDateIsRejected() throws Exception {
        Session user = register("an-baddate-");
        MvcResult result = call(user, get("/api/v1/analytics/activity?from=not-a-date"));
        assertStructuredError(result, 400, "Bad Request");
    }
}
