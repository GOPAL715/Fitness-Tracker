package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 14 item 12: two-user analytics isolation.
 *
 * <p>User A and User B receive deliberately distinct values across every analytics dimension, so
 * an accidental cross-user aggregation is detectable rather than merely absent.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class AnalyticsTwoUserAcceptanceTest extends AbstractAcceptanceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 5, 20);

    private Session a;
    private Session b;
    /** V3 enforces a unique index on ai_usage.request_id, so each test uses fresh ids. */
    private String requestIdA;
    private String requestIdB;

    @BeforeEach
    void seedDistinctDataForBothUsers() throws Exception {
        a = register("iso-a-");
        b = register("iso-b-");
        requestIdA = UUID.randomUUID().toString();
        requestIdB = UUID.randomUUID().toString();

        metric(a.id(), 1111);
        metric(b.id(), 9999);
        meal(a.id(), "111");
        meal(b.id(), "999");
        bodyWeight(a.id(), "111.5");
        bodyWeight(b.id(), "199.5");
        workout(a.id(), 11);
        workout(b.id(), 99);
        habit(a.id(), 1);
        habit(b.id(), 3);
        usage(a.id(), requestIdA);
        usage(b.id(), requestIdB);
    }

    private void metric(String userId, int steps) {
        metric(userId, steps, DAY);
    }

    private void metric(String userId, int steps, LocalDate date) {
        jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps,sleep_hours,calories_burned,water_oz,active_minutes)"
                        + " values (?,?::uuid,?,?,?,?,?,?)",
                UUID.randomUUID(), userId, date, steps, 7.5, 200, 50, 30);
    }

    private void meal(String userId, String calories) {
        meal(userId, calories, DAY);
    }

    private void meal(String userId, String calories, LocalDate date) {
        jdbc.update("insert into meals(id,user_id,meal_date,meal_type,name,source,calories,protein_g,carbs_g,fat_g)"
                        + " values (?,?::uuid,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), userId, date, "LUNCH", "Isolated meal", "manual",
                new BigDecimal(calories), new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("5"));
    }

    private void bodyWeight(String userId, String weight) {
        bodyWeight(userId, weight, DAY);
    }

    private void bodyWeight(String userId, String weight, LocalDate date) {
        jdbc.update("insert into body_metrics(id,user_id,metric_date,weight_lb,body_fat_pct) values (?,?::uuid,?,?,?)",
                UUID.randomUUID(), userId, date, new BigDecimal(weight), new BigDecimal("20"));
    }

    private void workout(String userId, int minutes) {
        workout(userId, minutes, DAY);
    }

    private void workout(String userId, int minutes, LocalDate date) {
        jdbc.update("insert into workouts(id,user_id,title,workout_date,duration_minutes,calories_burned,completed)"
                        + " values (?,?::uuid,?,?,?,?,true)",
                UUID.randomUUID(), userId, "Isolated workout", date, minutes, 100);
    }

    private void habit(String userId, int completedLogs) {
        UUID habitId = UUID.randomUUID();
        jdbc.update("insert into habits(id,user_id,name) values (?,?::uuid,?)", habitId, userId, "Isolated habit");
        for (int i = 0; i < completedLogs; i++) {
            jdbc.update("insert into habit_logs(id,user_id,habit_id,log_date,completed) values (?,?::uuid,?,?,true)",
                    UUID.randomUUID(), userId, habitId, DAY.plusDays(i));
        }
    }

    private void usage(String userId, String requestId) {
        jdbc.update("insert into ai_usage(id,user_id,feature,model,provider,request_id,input_tokens,output_tokens,"
                        + "total_tokens,success,estimated_cost,latency_ms) values (?,?::uuid,'food_scan','m','p',?,5,5,10,true,?,3)",
                UUID.randomUUID(), userId, UUID.fromString(requestId), new BigDecimal("0.5"));
    }

    private JsonNode analytics(Session user, String path) throws Exception {
        var result = getAs(user, path);
        assertStatus(result, 200);
        return json(result);
    }

    @Test
    @DisplayName("Phase 14 item 12 - activity and nutrition analytics contain only the caller's data")
    void activityAndNutritionAreIsolated() throws Exception {
        JsonNode activity = analytics(a, "/api/v1/analytics/activity?from=2026-05-20&to=2026-05-20");
        assertThat(activity).hasSize(1);
        assertThat(activity.get(0).path("steps").asInt()).as("A sees A's steps").isEqualTo(1111);
        assertThat(activity.toString()).as("B's steps never appear for A").doesNotContain("9999");

        JsonNode nutrition = analytics(a, "/api/v1/analytics/nutrition?from=2026-05-20&to=2026-05-20");
        assertThat(nutrition.path("totals").path("calories").asInt())
                .as("A sees only A's calories").isEqualTo(111);
        assertThat(nutrition.toString()).doesNotContain("999");
    }

    @Test
    @DisplayName("Phase 14 item 12 - body, workout, habit and calendar analytics are isolated")
    void bodyWorkoutHabitAndCalendarAreIsolated() throws Exception {
        JsonNode progress = analytics(a, "/api/v1/analytics/progress?from=2026-05-20&to=2026-05-20");
        assertThat(progress.path("body")).hasSize(1);
        assertThat(progress.path("body").get(0).path("weight_lb").asDouble()).isEqualTo(111.5);
        assertThat(progress.toString()).as("B's weight never appears for A").doesNotContain("199.5");

        JsonNode workouts = analytics(a, "/api/v1/analytics/workouts?from=2026-05-20&to=2026-05-20");
        assertThat(workouts).hasSize(1);
        assertThat(workouts.get(0).path("minutes").asInt()).as("A sees A's minutes").isEqualTo(11);

        JsonNode habits = analytics(a, "/api/v1/analytics/habits?from=2026-05-20&to=2026-05-25");
        assertThat(habits).hasSize(1);
        assertThat(habits.get(0).path("completed").asInt())
                .as("A completed one habit log, not B's three").isEqualTo(1);

        JsonNode calendar = analytics(a, "/api/v1/analytics/calendar?from=2026-05-20&to=2026-05-20");
        assertThat(calendar).hasSize(1);
        JsonNode summary = calendar.get(0).path("summary");
        assertThat(summary.path("steps").asInt()).isEqualTo(1111);
        assertThat(summary.path("workouts").asInt()).as("A's workout only").isEqualTo(1);
        assertThat(summary.path("habits_completed").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 14 item 12 - AI usage and dashboard analytics are isolated")
    void aiUsageAndDashboardAreIsolated() throws Exception {
        JsonNode usage = analytics(a, "/api/v1/analytics/ai-usage");
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).path("user_id").asText()).isEqualTo(a.id());
        assertThat(usage.toString())
                .as("B's request id never appears for A")
                .doesNotContain(requestIdB);
        assertThat(usage.toString()).contains(requestIdA);

        // /dashboard is scoped to today by contract, so point the fixture at today.
        LocalDate today = LocalDate.now();
        metric(a.id(), 1111, today);
        bodyWeight(a.id(), "111.5", today);
        workout(a.id(), 11, today);
        meal(a.id(), "111", today);
        JsonNode dashboard = analytics(a, "/api/v1/analytics/dashboard");
        assertThat(dashboard.path("date").asText()).as("dashboard reports today").isEqualTo(today.toString());
        assertThat(dashboard.path("activity").path("steps").asInt()).isEqualTo(1111);
        assertThat(dashboard.path("nutrition").path("calories").asInt()).isEqualTo(111);
        assertThat(dashboard.path("workout").path("minutes").asInt()).isEqualTo(11);
        assertThat(dashboard.path("body").path("weight_lb").asDouble()).isEqualTo(111.5);
    }

    @Test
    @DisplayName("Phase 14 item 12 - the reverse direction is equally isolated")
    void userBSeesOnlyTheirOwnData() throws Exception {
        JsonNode activity = analytics(b, "/api/v1/analytics/activity?from=2026-05-20&to=2026-05-20");
        assertThat(activity.get(0).path("steps").asInt()).isEqualTo(9999);
        assertThat(activity.toString()).as("A's steps never appear for B").doesNotContain("1111");

        JsonNode usage = analytics(b, "/api/v1/analytics/ai-usage");
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).path("user_id").asText()).isEqualTo(b.id());
        assertThat(usage.toString()).doesNotContain(requestIdA);
    }

    @Test
    @DisplayName("Phase 14 item 12 - analytics and AI endpoints require authentication")
    void analyticsRequireAuthentication() throws Exception {
        assertUnauthenticated(get("/api/v1/analytics/ai-usage"));
        assertUnauthenticated(get("/api/v1/analytics/dashboard"));
        assertThat(mvc.perform(post("/api/v1/coach/analyze")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
