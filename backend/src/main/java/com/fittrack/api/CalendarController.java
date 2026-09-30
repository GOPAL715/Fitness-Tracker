package com.fittrack.api;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * The calendar's single source of truth.
 *
 * <p>One bounded request answers everything the calendar grid, the day panel and the history
 * timeline need. It deliberately does not reuse {@code /app-data}: that endpoint returns every row a
 * user has ever written, which grows without limit, whereas this one reads only the requested window.
 *
 * <p>Every query here is a single range query over the window, so the number of statements is fixed
 * no matter how many days are requested. The previous implementation looped over the range and
 * issued five queries per day, which made a year-long request cost roughly eighteen hundred
 * statements.
 *
 * <p>Sessions are grouped by {@code session_date}, the calendar day the client recorded. Grouping by
 * {@code started_at::date} used the UTC date and filed any session completed before the user's local
 * midnight under the previous day. Rows written before that column existed have a null
 * {@code session_date} and are skipped rather than attributed to a day they are not known to be on.
 */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {
    /** The widest window one request may ask for, matching the previous endpoint's documented bound. */
    static final int MAX_RANGE_DAYS = 366;

    private final NamedParameterJdbcTemplate jdbc;
    public CalendarController(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/summary")
    public CalendarSummary summary(@RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @AuthenticationPrincipal String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Authenticated user is required");
        // The one shared inclusive-range contract. This used to compare from.plusDays(366).isBefore(to),
        // which accepted one day more than the analytics endpoints and let the two disagree about the
        // same window; AnalyticsRange is now the single rule for both.
        com.fittrack.analytics.AnalyticsRange.validate(from, to);
        // One parameter set for every query below, so the owner is the authenticated user in each of
        // them and there is no way to read another account's history.
        return new CalendarSummary(from, to, days(from, to, userId), sessions(from, to, userId),
                meals(from, to, userId), habits(from, to, userId), body(from, to, userId), records(from, to, userId));
    }

    /** Daily totals, keyed by date, for the day grid and the day panel. */
    private List<CalendarDay> days(LocalDate from, LocalDate to, String userId) {
        // UNION ALL rather than one query per table per day: seven range scans regardless of window size.
        String sql = """
                SELECT metric_date::text AS date, steps, water_oz, calories_burned, active_minutes, sleep_hours, readiness
                  FROM v_daily_metrics_canonical WHERE user_id=CAST(:u AS uuid) AND metric_date BETWEEN :f AND :t
                UNION ALL
                SELECT workout_date::text, NULL, NULL, NULL, NULL, NULL, NULL
                  FROM workouts WHERE user_id=CAST(:u AS uuid) AND workout_date BETWEEN :f AND :t AND completed
                UNION ALL
                SELECT session_date::text, NULL, NULL, NULL, NULL, NULL, NULL
                  FROM workout_sessions WHERE user_id=CAST(:u AS uuid) AND session_date BETWEEN :f AND :t AND completed
                UNION ALL
                SELECT meal_date::text, NULL, NULL, NULL, NULL, NULL, NULL
                  FROM meals WHERE user_id=CAST(:u AS uuid) AND meal_date BETWEEN :f AND :t
                UNION ALL
                SELECT l.log_date::text, NULL, NULL, NULL, NULL, NULL, NULL
                  FROM habit_logs l WHERE l.user_id=CAST(:u AS uuid) AND l.log_date BETWEEN :f AND :t AND l.completed
                UNION ALL
                SELECT metric_date::text, NULL, NULL, NULL, NULL, NULL, NULL
                  FROM v_body_metrics_canonical WHERE user_id=CAST(:u AS uuid) AND metric_date BETWEEN :f AND :t
                UNION ALL
                SELECT achieved_date::text, NULL, NULL, NULL, NULL, NULL, NULL
                  FROM personal_records WHERE user_id=CAST(:u AS uuid) AND achieved_date BETWEEN :f AND :t
                ORDER BY 1
                """;
        return jdbc.query(sql, new MapSqlParameterSource("u", userId).addValue("f", from).addValue("t", to),
                (rs, n) -> new CalendarDay(rs.getString("date"),
                        rs.getObject("steps", Integer.class), rs.getObject("water_oz", Integer.class),
                        rs.getObject("calories_burned", Integer.class), rs.getObject("active_minutes", Integer.class),
                        rs.getObject("sleep_hours", java.math.BigDecimal.class), rs.getObject("readiness", Integer.class)));
    }

    private List<CalendarSession> sessions(LocalDate from, LocalDate to, String userId) {
        return jdbc.query("SELECT id::text, session_date::text, title, workout_type, duration_minutes, perceived_effort "
                        + "FROM workout_sessions WHERE user_id=CAST(:u AS uuid) AND session_date BETWEEN :f AND :t "
                        + "AND completed ORDER BY session_date, created_at, id",
                new MapSqlParameterSource("u", userId).addValue("f", from).addValue("t", to),
                (rs, n) -> new CalendarSession(rs.getString("id"), rs.getString("session_date"),
                        rs.getString("title"), rs.getString("workout_type"),
                        rs.getObject("duration_minutes", Integer.class), rs.getObject("perceived_effort", Integer.class)));
    }

    private List<CalendarMeal> meals(LocalDate from, LocalDate to, String userId) {
        return jdbc.query("SELECT id::text, meal_date::text, meal_type, name, calories FROM meals "
                        + "WHERE user_id=CAST(:u AS uuid) AND meal_date BETWEEN :f AND :t ORDER BY meal_date, created_at, id",
                new MapSqlParameterSource("u", userId).addValue("f", from).addValue("t", to),
                (rs, n) -> new CalendarMeal(rs.getString("id"), rs.getString("meal_date"),
                        rs.getString("meal_type"), rs.getString("name"),
                        rs.getObject("calories", java.math.BigDecimal.class)));
    }

    private List<CalendarHabit> habits(LocalDate from, LocalDate to, String userId) {
        return jdbc.query("SELECT l.id::text, l.log_date::text, h.name FROM habit_logs l "
                        + "JOIN habits h ON h.id = l.habit_id AND h.user_id = l.user_id "
                        + "WHERE l.user_id=CAST(:u AS uuid) AND l.log_date BETWEEN :f AND :t AND l.completed "
                        + "ORDER BY l.log_date, h.name, l.id",
                new MapSqlParameterSource("u", userId).addValue("f", from).addValue("t", to),
                (rs, n) -> new CalendarHabit(rs.getString("id"), rs.getString("log_date"), rs.getString("name")));
    }

    private List<CalendarBody> body(LocalDate from, LocalDate to, String userId) {
        return jdbc.query("SELECT id::text, metric_date::text, weight_lb, body_fat_pct, waist_in FROM body_metrics "
                        + "WHERE user_id=CAST(:u AS uuid) AND metric_date BETWEEN :f AND :t ORDER BY metric_date, id",
                new MapSqlParameterSource("u", userId).addValue("f", from).addValue("t", to),
                (rs, n) -> new CalendarBody(rs.getString("id"), rs.getString("metric_date"),
                        rs.getObject("weight_lb", java.math.BigDecimal.class),
                        rs.getObject("body_fat_pct", java.math.BigDecimal.class),
                        rs.getObject("waist_in", java.math.BigDecimal.class)));
    }

    private List<CalendarRecord> records(LocalDate from, LocalDate to, String userId) {
        // Rows with no achieved_date predate the requirement that one is supplied. They cannot be
        // placed on a calendar, so they are omitted here rather than shown on a day they are not known
        // to fall on; they remain readable through their own resource.
        return jdbc.query("SELECT id::text, achieved_date::text, exercise, record_value, unit FROM personal_records "
                        + "WHERE user_id=CAST(:u AS uuid) AND achieved_date IS NOT NULL AND achieved_date BETWEEN :f AND :t "
                        + "ORDER BY achieved_date, id",
                new MapSqlParameterSource("u", userId).addValue("f", from).addValue("t", to),
                (rs, n) -> new CalendarRecord(rs.getString("id"), rs.getString("achieved_date"),
                        rs.getString("exercise"), rs.getObject("record_value", java.math.BigDecimal.class),
                        rs.getString("unit")));
    }

    public record CalendarSummary(LocalDate from, LocalDate to, List<CalendarDay> days,
                                  List<CalendarSession> sessions, List<CalendarMeal> meals,
                                  List<CalendarHabit> habitLogs, List<CalendarBody> bodyMetrics,
                                  List<CalendarRecord> personalRecords) {}

    /** One row per date that has any recorded activity; the grid derives its own empty cells. */
    public record CalendarDay(String date, Integer steps, Integer waterOz, Integer caloriesBurned,
                              Integer activeMinutes, java.math.BigDecimal sleepHours, Integer readiness) {}

    public record CalendarSession(String id, String date, String title, String workoutType,
                                  Integer durationMinutes, Integer perceivedEffort) {}

    public record CalendarMeal(String id, String date, String mealType, String name, java.math.BigDecimal calories) {}

    public record CalendarHabit(String id, String date, String name) {}

    public record CalendarBody(String id, String date, java.math.BigDecimal weightLb,
                               java.math.BigDecimal bodyFatPct, java.math.BigDecimal waistIn) {}

    public record CalendarRecord(String id, String date, String exercise, java.math.BigDecimal recordValue, String unit) {}

    /**
     * A dense day-by-day list, one entry per date in the window, for the analytics calendar.
     *
     * <p>Kept because the analytics calendar is defined as a dense series: it must report a zeroed
     * entry for a day with nothing recorded, which a presence-only list cannot express. It is a
     * single grouped query, not a loop, so it costs the same whatever the window.
     */
    public List<Map<String,Object>> dailyTotals(LocalDate from, LocalDate to, String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Authenticated user is required");
        if (from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
        String sql = """
                WITH d AS (
                  SELECT metric_date AS date, steps, water_oz, calories_burned, active_minutes, sleep_hours
                    FROM v_daily_metrics_canonical WHERE user_id=CAST(:u AS uuid) AND metric_date BETWEEN :f AND :t
                ), w AS (
                  SELECT workout_date AS date, count(*) AS n FROM workouts
                    WHERE user_id=CAST(:u AS uuid) AND workout_date BETWEEN :f AND :t AND completed GROUP BY 1
                ), s AS (
                  SELECT session_date AS date, count(*) AS n FROM workout_sessions
                    WHERE user_id=CAST(:u AS uuid) AND session_date BETWEEN :f AND :t AND completed GROUP BY 1
                ), m AS (
                  SELECT meal_date AS date, count(*) AS n FROM meals
                    WHERE user_id=CAST(:u AS uuid) AND meal_date BETWEEN :f AND :t GROUP BY 1
                ), h AS (
                  SELECT log_date AS date, count(*) AS n FROM habit_logs
                    WHERE user_id=CAST(:u AS uuid) AND log_date BETWEEN :f AND :t AND completed GROUP BY 1
                ), days AS (
                  SELECT generate_series(:f::date, :t::date, interval '1 day')::date AS date
                )
                SELECT g.date::text AS date,
                       COALESCE(d.steps,0) AS steps, COALESCE(d.water_oz,0) AS water_oz,
                       COALESCE(d.calories_burned,0) AS calories_burned,
                       COALESCE(d.active_minutes,0) AS active_minutes, COALESCE(d.sleep_hours,0) AS sleep_hours,
                       COALESCE(w.n,0) + COALESCE(s.n,0) AS workouts,
                       COALESCE(m.n,0) AS meals, COALESCE(h.n,0) AS habits_completed
                  FROM days g
                  LEFT JOIN d ON d.date = g.date
                  LEFT JOIN w ON w.date = g.date
                  LEFT JOIN s ON s.date = g.date
                  LEFT JOIN m ON m.date = g.date
                  LEFT JOIN h ON h.date = g.date
                 ORDER BY g.date
                """;
        return jdbc.queryForList(sql, new MapSqlParameterSource("u", userId).addValue("f", from).addValue("t", to));
    }
}
