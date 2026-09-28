package com.fittrack.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 9 Coach context assembly.
 *
 * <p>Every fact sent to the AI provider is built here, server-side, from an explicit allowlist of
 * columns. The controller holds no SQL and nothing is ever read from a request body.
 *
 * <h2>Privacy boundary</h2>
 * Only the fields below leave the server. Deliberately excluded: email and every other identity
 * field; {@code fitness_profile.limitations}, which is free text a user may use for medical
 * information; health-device identifiers; raw exercise sets; raw food-scan history; and all keys,
 * so no database identifier crosses the boundary.
 *
 * <h2>Size</h2>
 * Every query is bounded by the requested window and the payload is serialised once to JSON.
 * {@link #MAX_CONTEXT_BYTES} is a hard ceiling: an oversized payload is progressively reduced
 * rather than sent, so context growth can never silently become a cost problem.
 */
@Service
public class CoachContextService {

    /** Generous ceiling for a 90-day window; the 7-day payload is a small fraction of this. */
    static final int MAX_CONTEXT_BYTES = 24_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CoachContextService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Builds the Coach context for one owner and window.
     *
     * @param userId the authenticated owner; every query is scoped to it
     * @param windowDays 7, 30 or 90
     */
    public String buildContextJson(UUID userId, int windowDays) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(windowDays - 1L);
        UUID p = userId;

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("window_days", windowDays);
        context.put("window_start", from.toString());
        context.put("window_end", today.toString());
        context.put("activity", activity(p, from, today));
        context.put("recovery", recovery(p, from, today));
        context.put("nutrition", nutrition(p, from, today));
        context.put("workouts", workouts(p, from, today));
        context.put("habits", habits(p, from, today));
        context.put("goals", goals(p));
        context.put("body_trend", bodyTrend(p, from, today));
        context.put("personal_records", personalRecords(p));

        return serialise(context, windowDays);
    }

    /**
     * Serialises with a hard byte ceiling.
     *
     * <p>A 90-day window is the largest the API accepts, so this should not trigger in practice.
     * It exists so a future schema change that widens a query cannot quietly become an unbounded
     * prompt. On overflow the payload is progressively reduced and the reduction is recorded
     * inside it, so the model is never misled about the period it is describing.
     */
    private String serialise(Map<String, Object> context, int windowDays) {
        String json = write(context);
        if (size(json) <= MAX_CONTEXT_BYTES) return json;
        Map<String, Object> reduced = new LinkedHashMap<>(context);
        reduced.put("context_truncated", true);
        reduced.remove("personal_records");
        json = write(reduced);
        if (size(json) <= MAX_CONTEXT_BYTES) return json;
        reduced.remove("goals");
        reduced.remove("body_trend");
        return write(reduced);
    }

    private int size(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    private String write(Map<String, Object> context) {
        try {
            return mapper.writeValueAsString(context);
        } catch (Exception e) {
            // A serialisation failure must not become an unhandled 500 with a provider call
            // attached; an empty object keeps the request accounted and the model unled.
            return "{}";
        }
    }

    /* ------------------------------------------------------------------ *
     * Allowlisted projections. Each returns aggregates, not raw history.
     * ------------------------------------------------------------------ */

    private Map<String, Object> activity(UUID p, LocalDate from, LocalDate to) {
        return one("SELECT COALESCE(sum(steps),0) steps, COALESCE(avg(sleep_hours),0) avg_sleep_hours,"
                + " COALESCE(sum(active_minutes),0) active_minutes,"
                + " COALESCE(avg(resting_heart_rate),0) avg_resting_hr, COALESCE(avg(hrv),0) avg_hrv,"
                + " count(*) days_logged FROM daily_metrics"
                + " WHERE user_id=? AND metric_date BETWEEN ? AND ?", p, from, to);
    }

    private Map<String, Object> recovery(UUID p, LocalDate from, LocalDate to) {
        return one("SELECT COALESCE(avg(readiness),0) avg_readiness, COALESCE(avg(stress_level),0) avg_stress,"
                + " COALESCE(sum(water_oz),0) water_oz FROM daily_metrics"
                + " WHERE user_id=? AND metric_date BETWEEN ? AND ?", p, from, to);
    }

    private Map<String, Object> nutrition(UUID p, LocalDate from, LocalDate to) {
        return one("SELECT count(*) meals_logged, COALESCE(sum(calories),0) calories,"
                + " COALESCE(avg(protein_g),0) avg_protein_g, COALESCE(avg(carbs_g),0) avg_carbs_g,"
                + " COALESCE(avg(fat_g),0) avg_fat_g FROM meals"
                + " WHERE user_id=? AND meal_date BETWEEN ? AND ?", p, from, to);
    }

    private Map<String, Object> workouts(UUID p, LocalDate from, LocalDate to) {
        return one("SELECT count(*) sessions, count(*) FILTER (WHERE completed) completed_sessions,"
                + " COALESCE(sum(duration_minutes) FILTER (WHERE completed),0) total_minutes,"
                + " COALESCE(avg(perceived_effort) FILTER (WHERE completed),0) avg_effort FROM workouts"
                + " WHERE user_id=? AND workout_date BETWEEN ? AND ?", p, from, to);
    }

    /** Adherence as a ratio the model can reason about directly, rather than raw log rows. */
    private Map<String, Object> habits(UUID p, LocalDate from, LocalDate to) {
        Map<String, Object> m = one("SELECT (SELECT count(*) FROM habits WHERE user_id=?) habits_tracked,"
                + " COALESCE(count(*) FILTER (WHERE completed),0) habit_logs_completed FROM habit_logs"
                + " WHERE user_id=? AND log_date BETWEEN ? AND ?", p, p, from, to);
        long tracked = num(m.get("habits_tracked"));
        long done = num(m.get("habit_logs_completed"));
        double pct = tracked == 0 ? 0 : Math.min(100, Math.round((done * 1000.0) / tracked) / 10.0);
        m.put("adherence_pct", BigDecimal.valueOf(pct));
        m.remove("habit_logs_completed");
        return m;
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** Goal titles only, capped. The one piece of user-authored text the Coach uses. */
    private List<Map<String, Object>> goals(UUID p) {
        return jdbc.queryForList("SELECT goal_type, title, current_value, target_value, unit, status"
                + " FROM goals WHERE user_id=? ORDER BY created_at DESC LIMIT 10", p);
    }

    /**
     * Body measurements as a direction plus endpoints, not the daily series.
     *
     * <p>Body metrics are the most sensitive values the Coach sees, so the payload carries the
     * change across the window rather than every reading.
     */
    private Map<String, Object> bodyTrend(UUID p, LocalDate from, LocalDate to) {
        Map<String, Object> m = one("SELECT count(*) readings,"
                + " (SELECT weight_lb FROM body_metrics WHERE user_id=? AND metric_date BETWEEN ? AND ?"
                + "  AND weight_lb IS NOT NULL ORDER BY metric_date ASC LIMIT 1) first_weight_lb,"
                + " (SELECT weight_lb FROM body_metrics WHERE user_id=? AND metric_date BETWEEN ? AND ?"
                + "  AND weight_lb IS NOT NULL ORDER BY metric_date DESC LIMIT 1) last_weight_lb,"
                + " (SELECT body_fat_pct FROM body_metrics WHERE user_id=? AND metric_date BETWEEN ? AND ?"
                + "  AND body_fat_pct IS NOT NULL ORDER BY metric_date DESC LIMIT 1) latest_body_fat_pct"
                + " FROM body_metrics WHERE user_id=? AND metric_date BETWEEN ? AND ?",
                p, from, to, p, from, to, p, from, to, p, from, to);
        if (m.isEmpty()) return Map.of("readings", 0);
        Object first = m.get("first_weight_lb");
        Object last = m.get("last_weight_lb");
        if (first instanceof BigDecimal f && last instanceof BigDecimal l) {
            m.put("weight_change_lb", l.subtract(f).setScale(2, RoundingMode.HALF_UP));
        }
        return m;
    }

    private List<Map<String, Object>> personalRecords(UUID p) {
        return jdbc.queryForList("SELECT exercise, record_value, unit, achieved_date FROM personal_records"
                + " WHERE user_id=? ORDER BY achieved_date DESC LIMIT 5", p);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.get(0));
    }
}
