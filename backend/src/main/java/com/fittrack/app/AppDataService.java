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

    /**
     * Read source for tables whose projection must collapse multiple source rows.
     *
     * <p>Phase 10 lets a day hold a manual row and one row per connected device. Everything the app
     * reads has to see one value per day, so the two metric tables are read through their canonical
     * views here. Without this the app data payload returns two rows for a single date and the UI
     * renders the day twice.
     */
    /**
     * The columns {@code health_devices} may contribute to the app-data payload (Phase 20).
     *
     * <h2>What this fixes</h2>
     * This table was read with {@code SELECT *}, so every column it grows is published to the browser
     * automatically. That had already leaked two:
     * <ul>
     *   <li>{@code sync_cursor} - the server's internal day watermark. It is not a cosmetic value: it
     *       decides the window the next sync re-reads, and {@code HealthSyncService} already falls
     *       back to a bounded window when it cannot parse one. A client should never be able to read
     *       or infer it, and {@code docs/database.md} already states it is never returned by the API.
     *       The dedicated {@code /api/v1/health/devices} route excluded it; this aggregate route did
     *       not, so an exclusion was undone by a second reader.</li>
     *   <li>{@code client_changes_token} - the Android client's own opaque Health Connect resume
     *       handle. V11 documents it as a strictly separate concept from the server's cursor precisely
     *       so the two cannot be conflated; publishing it to a browser hands the client's resumption
     *       state to a third party for no benefit.</li>
     * </ul>
     *
     * <h2>Why an allowlist rather than a denylist</h2>
     * A denylist of the two known offenders would leave the next column added to this table exposed by
     * default, which is how the first leak happened. The allowlist inverts that: a column becomes
     * visible because it was named here, which is the same rule {@code HealthDeviceResponse} and
     * {@code HealthIntegrationController} already follow.
     *
     * <p>{@code user_id} is excluded too. The payload is already owner-scoped, so echoing the subject
     * back adds nothing a client does not already hold.
     */
    private static final Map<String, String> DEVICE_PROJECTION = Map.ofEntries(
        Map.entry("id", "id"),
        Map.entry("device_name", "device_name"),
        Map.entry("device_type", "device_type"),
        Map.entry("status", "status"),
        Map.entry("provider", "provider"),
        Map.entry("external_device_id", "external_device_id"),
        Map.entry("last_sync", "last_sync"),
        Map.entry("sync_status", "sync_status"),
        Map.entry("last_error", "last_error"),
        Map.entry("permission_status", "permission_status"),
        Map.entry("created_at", "created_at")
    );

    /**
     * Read source for tables whose projection must collapse multiple source rows.
     *
     * <p>Phase 10 lets a day hold a manual row and one row per connected device. Everything the app
     * reads has to see one value per day, so the two metric tables are read through their canonical
     * views here. Without this the app data payload returns two rows for a single date and the UI
     * renders the day twice.
     */
    private static String readSource(String table) {
        return switch (table) {
            case "daily_metrics" -> "v_daily_metrics_canonical";
            case "body_metrics" -> "v_body_metrics_canonical";
            default -> table;
        };
    }

    /**
     * The selected column list for a table.
     *
     * <p>{@code health_devices} gets an explicit list; every other table keeps {@code *}, which is
     * deliberate and out of scope to change here. Those tables hold no secret and no resumable cursor,
     * and rewriting eight unrelated projections would put this fix at risk for no security benefit.
     * The one table that stores a credential-bearing cursor is now handled.
     */
    private static String columns(String table) {
        return "health_devices".equals(table) ? DEVICE_COLUMNS : "*";
    }

    /** The device columns in a stable order, built once from the allowlist. */
    private static final String DEVICE_COLUMNS = String.join(",", DEVICE_PROJECTION.values());

    @Transactional(readOnly = true)
    public Map<String,Object> load(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Authenticated user is required");
        Map<String,Object> result = new LinkedHashMap<>();
        for (String table : USER_TABLES) {
            // The column list is built from an allowlist rather than written inline, so the same
            // projection cannot drift between the map that documents it and the query that uses it.
            String sql = "SELECT " + columns(table) + " FROM " + readSource(table)
                    + " WHERE user_id = CAST(? AS uuid)";
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
