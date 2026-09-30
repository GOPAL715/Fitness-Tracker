package com.fittrack.analytics;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * The single definition of "a training activity", shared by every analytics and calendar read.
 *
 * <h2>Why this exists</h2>
 * FitTrack stores a performed session in one of two tables, and the Phase 14 audit found the two
 * readers disagreed about what a workout is. {@code /analytics/workouts} counted only
 * {@code workouts}, while {@code /analytics/calendar} counted {@code workouts} <em>plus</em>
 * {@code workout_sessions}. The same user, the same day, two different answers from two endpoints
 * that both claim to report training activity.
 *
 * <h2>The two tables are different concepts, not duplicates</h2>
 * <ul>
 *   <li>{@code workouts} is a <b>quick log</b>: one flat row a user types in a form, carrying
 *       {@code calories_burned}, {@code distance_miles} and {@code intensity}. Written by the
 *       Workouts screen. It has no exercises and no sets.</li>
 *   <li>{@code workout_sessions} is a <b>structured session</b>: a session header with child
 *       {@code workout_exercises} and {@code exercise_sets}, written by
 *       {@code POST /workout-sessions/complete}. It carries {@code perceived_effort} and has no
 *       calorie or distance column at all.</li>
 * </ul>
 *
 * <p>A user does both, and neither is a copy of the other. Forcing them into one table would discard
 * either the typed calorie estimate or the per-set detail, so the distinction is preserved and
 * reported rather than erased. What is unified is the <em>answer</em>: "did this person train on
 * this day" is true whether they quick-logged it or logged every set.
 */
@Component
public class WorkoutAnalytics {

    private final NamedParameterJdbcTemplate jdbc;

    public WorkoutAnalytics(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Completed training activity per day in the window.
     *
     * <p>One grouped query over both tables, so the cost is fixed whatever the window. Rows are
     * emitted for days that have activity only; a day with none is simply absent, and a caller
     * wanting a dense calendar uses {@code /analytics/calendar}.
     */
    public List<DailyWorkout> dailyTotals(LocalDate from, LocalDate to, String userId) {
        String sql = """
                WITH q AS (
                  SELECT workout_date AS d, 1 AS quick, duration_minutes AS mins,
                         calories_burned AS cals, 1 AS has_cals
                    FROM workouts
                   WHERE user_id=CAST(:u AS uuid) AND workout_date BETWEEN :f AND :t AND completed
                  UNION ALL
                  SELECT session_date, 0, duration_minutes, NULL, 0
                    FROM workout_sessions
                   WHERE user_id=CAST(:u AS uuid) AND session_date BETWEEN :f AND :t AND completed
                )
                SELECT d::text AS date,
                       sum(quick)::bigint            AS quick_logged,
                       (sum(1) - sum(quick))::bigint AS logged_sessions,
                       sum(1)::bigint                AS sessions,
                       COALESCE(sum(mins), 0)::bigint AS minutes,
                       -- A null here means at least one logged session had no recorded calories,
                       -- which is unknown, not zero. workout_sessions has no calorie column.
                       CASE WHEN sum(has_cals) < sum(1) THEN NULL
                            ELSE COALESCE(sum(cals), 0) END::bigint AS calories
                  FROM q
                 GROUP BY d
                 ORDER BY d
                """;
        return jdbc.query(sql, params(userId, from, to), (rs, n) -> new DailyWorkout(
                LocalDate.parse(rs.getString("date")),
                rs.getLong("quick_logged"),
                rs.getLong("logged_sessions"),
                rs.getLong("sessions"),
                rs.getLong("minutes"),
                (Long) rs.getObject("calories")));
    }

    private static MapSqlParameterSource params(String userId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("u", userId).addValue("f", from).addValue("t", to);
    }

    /**
     * Training activity for one day.
     *
     * @param quickLogged    completed rows in {@code workouts}
     * @param loggedSessions completed rows in {@code workout_sessions}
     * @param sessions       the total of both, which is the canonical activity count
     * @param minutes        summed duration across both
     * @param calories       quick-log calories, or null when a logged session in the window has no
     *                       recorded calories and the total is therefore not a real zero
     */
    public record DailyWorkout(LocalDate date, long quickLogged, long loggedSessions, long sessions,
                               long minutes, Long calories) {

        /** True when {@link #calories()} is a measured total rather than an absence of data. */
        public boolean caloriesKnown() {
            return calories != null;
        }
    }
}