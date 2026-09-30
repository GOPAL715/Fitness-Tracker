package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AiAssertions;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 22: the Coach must agree with the Phase 21 analytics layer.
 *
 * <p>Three defects are pinned here, each of which made the Coach contradict the user's own dashboard:
 * a quick log was counted but a structured session was not; "today" was UTC while the dashboard used
 * the user's zone; and fiber was missing from the nutrition context.
 *
 * <p>The context is read through the provider boundary, which is what production does. The fake
 * provider records the exact prompt it received, so these assert on the real payload rather than on
 * a separately built copy of it.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class CoachContextCorrectnessAcceptanceTest extends AiAssertions {

    private LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private void quickLog(String userId, LocalDate date, int minutes, int effort) {
        jdbc.update("insert into workouts(id,user_id,title,duration_minutes,workout_date,completed,"
                        + "perceived_effort) values (?,?::uuid,'Quick',?,?,true,?)",
                UUID.randomUUID(), userId, minutes, date, effort);
    }

    private void loggedSession(String userId, LocalDate date, int minutes, int effort) {
        jdbc.update("insert into workout_sessions(id,user_id,title,session_date,duration_minutes,"
                        + "completed,perceived_effort) values (?,?::uuid,'Logged',?,?,true,?)",
                UUID.randomUUID(), userId, date, minutes, effort);
    }

    private void meal(String userId, LocalDate date, String calories, String fiber) {
        jdbc.update("insert into meals(id,user_id,meal_date,meal_type,name,source,calories,"
                        + "protein_g,carbs_g,fat_g,fiber_g) values (?,?::uuid,?,'LUNCH','M','manual',"
                        + "?,10,20,5,?)",
                UUID.randomUUID(), userId, date, new BigDecimal(calories), new BigDecimal(fiber));
    }

    /** Asks the Coach and asserts it succeeded. */
    private MvcResult ask(Session user, int windowDays) throws Exception {
        MvcResult result = call(user, post("/api/v1/coach/insights")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"window_days\":" + windowDays + "}"));
        assertStatus(result, 200);
        return result;
    }


    /** The CONTEXT block of the prompt the fake provider actually received. */
    private JsonNode coachContext() throws Exception {
        String all = fake().lastCoachContext();
        assertThat(all).as("the provider must have been called").isNotNull();
        int at = all.indexOf("```json");
        assertThat(at).as("a fenced CONTEXT block must be present").isGreaterThanOrEqualTo(0);
        String json = all.substring(at + "```json".length(), all.indexOf("```", at + 7));
        return mapper.readTree(json);
    }

    private void setZone(String userId, String zone) {
        jdbc.update("update app_users set timezone=? where id=CAST(? as uuid)", zone, userId);
    }

    @Nested
    @DisplayName("Phase 22 R1 - canonical workout context")
    class WorkoutContext {

        @Test
        @DisplayName("a quick-log workout is visible to the Coach")
        void quickLogIsVisible() throws Exception {
            Session user = register("p22-quick-");
            quickLog(user.id(), today(), 40, 7);
            ask(user, 7);

            JsonNode workouts = coachContext().path("workouts");
            assertThat(workouts.path("sessions").asLong()).isEqualTo(1);
            assertThat(workouts.path("total_minutes").asLong()).isEqualTo(40);
        }

        @Test
        @DisplayName("a structured session is visible to the Coach")
        void loggedSessionIsVisible() throws Exception {
            // This is the regression: SessionLogger writes here, and the Coach previously read only
            // the legacy table, so a user with a full session log was told they had trained nothing.
            Session user = register("p22-session-");
            loggedSession(user.id(), today(), 55, 8);
            ask(user, 7);

            assertThat(coachContext().path("workouts").path("sessions").asLong())
                    .as("a logged session is training activity").isEqualTo(1);
        }

        @Test
        @DisplayName("both record types are counted together, once each")
        void bothRecordTypesCombine() throws Exception {
            Session user = register("p22-both-");
            quickLog(user.id(), today(), 30, 6);
            loggedSession(user.id(), today(), 45, 8);
            ask(user, 7);

            JsonNode workouts = coachContext().path("workouts");
            assertThat(workouts.path("sessions").asLong()).isEqualTo(2);
            assertThat(workouts.path("completed_sessions").asLong()).isEqualTo(2);
            assertThat(workouts.path("total_minutes").asLong()).isEqualTo(75);
        }

        @Test
        @DisplayName("the Coach count matches the analytics count for the same day")
        void coachAgreesWithAnalytics() throws Exception {
            Session user = register("p22-agree-");
            quickLog(user.id(), today(), 30, 6);
            loggedSession(user.id(), today(), 45, 8);

            JsonNode analytics = json(getAs(user, "/api/v1/analytics/workouts?from="
                    + today() + "&to=" + today()));
            assertThat(analytics).hasSize(1);
            ask(user, 7);

            assertThat(coachContext().path("workouts").path("sessions").asLong())
                    .as("the two surfaces must not disagree about the same day")
                    .isEqualTo(analytics.get(0).path("sessions").asLong());
        }

        @Test
        @DisplayName("a planned but uncompleted record is not training activity")
        void uncompletedIsNotCounted() throws Exception {
            Session user = register("p22-uncompleted-");
            jdbc.update("insert into workout_sessions(id,user_id,title,session_date,completed)"
                    + " values (?,?::uuid,'Planned',?,false)", UUID.randomUUID(), user.id(), today());
            ask(user, 7);
            assertThat(coachContext().path("workouts").path("sessions").asLong()).isZero();
        }

        @Test
        @DisplayName("average effort is pooled, not an average of two averages")
        void averageEffortIsPooled() throws Exception {
            // Three quick logs at 6 and one logged session at 9. An average-of-averages would report
            // 7.50; the pooled mean is (6+6+6+9)/4 = 6.75.
            Session user = register("p22-effort-");
            quickLog(user.id(), today(), 30, 6);
            quickLog(user.id(), today(), 30, 6);
            quickLog(user.id(), today(), 30, 6);
            loggedSession(user.id(), today(), 45, 9);
            ask(user, 7);

            assertThat(coachContext().path("workouts").path("avg_effort").asDouble())
                    .isEqualTo(6.75, offset(0.01));
        }
    }
    @Nested
    @DisplayName("Phase 22 R2 - timezone-correct window")
    class Timezone {

        @Test
        @DisplayName("the window end follows the user's stored zone, not UTC")
        void windowFollowsUserZone() throws Exception {
            Session user = register("p22-tz-");
            // UTC+14 is already tomorrow there while it is still today in UTC.
            setZone(user.id(), "Pacific/Kiritimati");
            LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);
            LocalDate localToday = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"));
            if (utcToday.equals(localToday)) {
                // Not near a date boundary today, so both rules agree and nothing is observable.
                return;
            }

            meal(user.id(), utcToday, "500", "10");
            ask(user, 7);

            assertThat(coachContext().path("window_end").asText())
                    .as("the Coach must end its window on the user's day, not UTC's")
                    .isEqualTo(localToday.toString());
        }

        @Test
        @DisplayName("a user with no stored zone falls back to UTC")
        void fallbackToUtc() throws Exception {
            Session user = register("p22-tz-fallback-");
            assertThat(jdbc.queryForObject("select timezone from app_users where id=CAST(? as uuid)",
                    String.class, user.id())).isNull();

            ask(user, 7);
            assertThat(coachContext().path("window_end").asText()).isEqualTo(today().toString());
        }

        @Test
        @DisplayName("the Coach window matches the analytics window for the same user")
        void coachAndAnalyticsShareTheBoundary() throws Exception {
            Session user = register("p22-tz-shared-");
            setZone(user.id(), "Asia/Kolkata");

            JsonNode dashboard = json(getAs(user, "/api/v1/analytics/dashboard"));
            ask(user, 7);

            assertThat(coachContext().path("window_end").asText())
                    .as("one timezone rule, so the two surfaces agree")
                    .isEqualTo(dashboard.path("date").asText());
        }

        @Test
        @DisplayName("the window includes the user's current day")
        void windowIsInclusive() throws Exception {
            Session user = register("p22-tz-inclusive-");
            setZone(user.id(), "Asia/Kolkata");
            LocalDate localToday = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
            meal(user.id(), localToday, "700", "12");
            ask(user, 7);

            JsonNode context = coachContext();
            assertThat(context.path("window_start").asText())
                    .isEqualTo(localToday.minusDays(6).toString());
            assertThat(context.path("nutrition").path("meals_logged").asLong())
                    .as("today's own meal is inside the window").isEqualTo(1);
        }
    }
    @Nested
    @DisplayName("Phase 22 R3 - nutrition fiber")
    class Fiber {

        @Test
        @DisplayName("fiber reaches the Coach context")
        void fiberIsIncluded() throws Exception {
            Session user = register("p22-fiber-");
            meal(user.id(), today(), "500", "11");
            ask(user, 7);

            assertThat(coachContext().path("nutrition").path("avg_fiber_g").asDouble()).isEqualTo(11.0);
        }

        @Test
        @DisplayName("fiber aggregates across meals alongside the other macros")
        void fiberAggregatesWithMacros() throws Exception {
            Session user = register("p22-fiber-multi-");
            meal(user.id(), today(), "500", "8");
            meal(user.id(), today(), "400", "12");
            ask(user, 7);

            JsonNode nutrition = coachContext().path("nutrition");
            assertThat(nutrition.path("avg_fiber_g").asDouble()).isEqualTo(10.0);
            assertThat(nutrition.path("meals_logged").asLong()).isEqualTo(2);
            // The pre-existing macros must be untouched by adding a column to the same query.
            assertThat(nutrition.path("avg_protein_g").asDouble()).isEqualTo(10.0);
            assertThat(nutrition.path("avg_carbs_g").asDouble()).isEqualTo(20.0);
            assertThat(nutrition.path("avg_fat_g").asDouble()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("a meal with null fiber does not break aggregation")
        void nullFiberIsSafe() throws Exception {
            Session user = register("p22-fiber-null-");
            jdbc.update("insert into meals(id,user_id,meal_date,meal_type,name,source,calories,"
                            + "protein_g,carbs_g,fat_g) values (?,?::uuid,?,'LUNCH','Old','manual',"
                            + "600,10,20,5)", UUID.randomUUID(), user.id(), today());
            meal(user.id(), today(), "400", "9");
            ask(user, 7);

            JsonNode nutrition = coachContext().path("nutrition");
            // avg ignores the null rather than counting it as a zero.
            assertThat(nutrition.path("avg_fiber_g").asDouble())
                    .as("only the meal that recorded fibre contributes").isEqualTo(9.0);
            assertThat(nutrition.path("calories").asDouble()).isEqualTo(1000);
        }
    }

    @Nested
    @DisplayName("Phase 22 - privacy boundary is unchanged")
    class Privacy {

        @Test
        @DisplayName("another account's activity never appears in the context")
        void crossUserActivityIsExcluded() throws Exception {
            Session mine = register("p22-priv-mine-");
            Session theirs = register("p22-priv-theirs-");
            loggedSession(theirs.id(), today(), 999, 10);
            meal(theirs.id(), today(), "9999", "99");

            ask(mine, 7);
            assertThat(fake().lastCoachContext()).doesNotContain("9999");
            assertThat(coachContext().path("workouts").path("total_minutes").asLong()).isZero();
        }

        @Test
        @DisplayName("no identity or free-text medical field is sent to the provider")
        void sensitiveFieldsStayOut() throws Exception {
            Session user = register("p22-priv-fields-");
            jdbc.update("update fitness_profile set limitations='recovering from a knee injury',"
                    + " display_name='Secret Name' where user_id=CAST(? as uuid)", user.id());

            ask(user, 7);
            String context = fake().lastCoachContext();
            assertThat(context).doesNotContain("knee injury");
            assertThat(context).doesNotContain("Secret Name");
        }
    }
}
