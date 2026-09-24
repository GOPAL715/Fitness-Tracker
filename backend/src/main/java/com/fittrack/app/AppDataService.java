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
            String sql = "SELECT * FROM " + table + " WHERE user_id = ?";
            List<Map<String,Object>> rows = jdbc.queryForList(sql, userId);
            if ("fitness_profile".equals(table)) result.put("profile", rows.isEmpty() ? null : rows.get(0));
            else result.put(NAMES.get(table), rows);
        }
        result.put("exercises", jdbc.queryForList("SELECT * FROM exercises ORDER BY name"));
        result.put("foods", jdbc.queryForList("SELECT * FROM foods ORDER BY name"));
        result.put("workoutExercises", jdbc.queryForList("SELECT we.* FROM workout_exercises we JOIN workout_sessions ws ON ws.id=we.workout_session_id WHERE ws.user_id=?", userId));
        result.put("exerciseSets", jdbc.queryForList("SELECT es.* FROM exercise_sets es JOIN workout_exercises we ON we.id=es.workout_exercise_id JOIN workout_sessions ws ON ws.id=we.workout_session_id WHERE ws.user_id=?", userId));
        result.put("templateExercises", jdbc.queryForList("SELECT te.* FROM workout_template_exercises te JOIN workout_templates t ON t.id=te.template_id WHERE t.user_id=?", userId));
        result.put("mealItems", jdbc.queryForList("SELECT mi.* FROM meal_items mi JOIN meals m ON m.id=mi.meal_id WHERE m.user_id=?", userId));
        result.put("foodScanItems", jdbc.queryForList("SELECT fi.* FROM food_scan_items fi JOIN food_scans fs ON fs.id=fi.scan_id WHERE fs.user_id=?", userId));
        return result;
    }
}
