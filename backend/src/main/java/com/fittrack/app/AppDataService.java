package com.fittrack.app;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AppDataService {
    private final JdbcTemplate jdbc;
    public AppDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final List<String> USER_TABLES = List.of(
        "fitness_profile", "daily_metrics", "workouts", "workout_sessions", "workout_templates", "plan_sessions",
        "personal_records", "meals", "food_scans", "ai_usage",
        "habits", "habit_logs", "goals", "reminders", "health_devices", "coach_notifications", "body_metrics"
    );
    private static final Map<String,String> NAMES = Map.ofEntries(
        Map.entry("fitness_profile","profile"), Map.entry("daily_metrics","metrics"),
        Map.entry("workouts","workouts"), Map.entry("workout_sessions","sessions"),
        Map.entry("workout_exercises","workoutExercises"), Map.entry("exercise_sets","exerciseSets"),
        Map.entry("workout_templates","templates"), Map.entry("workout_template_exercises","templateExercises"),
        Map.entry("plan_sessions","plan"), Map.entry("personal_records","records"),
        Map.entry("meals","meals"), Map.entry("meal_items","mealItems"), Map.entry("food_scans","foodScans"),
        Map.entry("food_scan_items","foodScanItems"), Map.entry("ai_usage","aiUsage"),
        Map.entry("habits","habits"), Map.entry("habit_logs","habitLogs"), Map.entry("goals","goals"),
        Map.entry("reminders","reminders"), Map.entry("health_devices","devices"),
        Map.entry("coach_notifications","notifications"), Map.entry("body_metrics","body")
    );

    @Transactional(readOnly = true)
    public Map<String,Object> load(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Authenticated user is required");
        Map<String,Object> result = new LinkedHashMap<>();
        for (String table : USER_TABLES) {
            String sql = "SELECT * FROM " + table + " WHERE user_id = CAST(? AS uuid)";
            List<Map<String,Object>> rows = jdbc.queryForList(sql, userId);
            if ("fitness_profile".equals(table)) result.put("profile", rows.isEmpty() ? null : rows.get(0));
            else result.put(NAMES.get(table), rows);
        }
        result.put("exercises", withSecondaryMuscles(jdbc.queryForList("SELECT * FROM exercises ORDER BY name")));
        result.put("foods", jdbc.queryForList("SELECT * FROM foods ORDER BY name"));
        result.put("workoutExercises", jdbc.queryForList("SELECT we.* FROM workout_exercises we JOIN workout_sessions ws ON ws.id=we.workout_session_id WHERE ws.user_id=CAST(? AS uuid)", userId));
        result.put("exerciseSets", jdbc.queryForList("SELECT es.* FROM exercise_sets es JOIN workout_exercises we ON we.id=es.workout_exercise_id JOIN workout_sessions ws ON ws.id=we.workout_session_id WHERE ws.user_id=CAST(? AS uuid)", userId));
        result.put("templateExercises", jdbc.queryForList("SELECT te.* FROM workout_template_exercises te JOIN workout_templates t ON t.id=te.template_id WHERE t.user_id=CAST(? AS uuid)", userId));
        result.put("mealItems", jdbc.queryForList("SELECT mi.* FROM meal_items mi JOIN meals m ON m.id=mi.meal_id WHERE m.user_id=CAST(? AS uuid)", userId));
        result.put("foodScanItems", jdbc.queryForList("SELECT fi.* FROM food_scan_items fi JOIN food_scans fs ON fs.id=fi.scan_id WHERE fs.user_id=CAST(? AS uuid)", userId));
        return result;
    }

    /**
     * Rewrites {@code secondary_muscles} from its stored form into the array the API contract declares.
     *
     * <p>The column is text holding a comma-separated list, but the domain type is {@code string[]} and
     * callers iterate it directly. Normalising here rather than in the database keeps the storage
     * format unchanged and fixes every consumer at once.
     *
     * <p>Each row is copied, not mutated in place, so the read-only transaction's result stays
     * independent and the original value is not aliased anywhere.
     *
     * @param exercises rows straight from {@code SELECT * FROM exercises}
     * @return the same rows with {@code secondary_muscles} replaced by a list
     */
    static List<Map<String,Object>> withSecondaryMuscles(List<Map<String,Object>> exercises) {
        List<Map<String,Object>> normalized = new ArrayList<>(exercises.size());
        for (Map<String,Object> row : exercises) {
            Map<String,Object> copy = new LinkedHashMap<>(row);
            copy.put("secondary_muscles", splitCsv(row.get("secondary_muscles")));
            normalized.add(copy);
        }
        return normalized;
    }

    /**
     * Splits a stored comma-separated value into trimmed, non-empty parts.
     *
     * <p>Null, blank and empty elements all collapse away, so a missing value becomes an empty list
     * rather than null or a list holding one empty string.
     */
    static List<String> splitCsv(Object value) {
        if (value == null) return List.of();
        List<String> parts = new ArrayList<>();
        for (String part : String.valueOf(value).split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }
}
