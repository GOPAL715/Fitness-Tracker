package com.fittrack.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/** Database-backed CRUD with a fixed, audited set of writable columns. */
@Service
public class OwnedResourceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OwnedResourceService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    private enum Scope { USER, CATALOG, WORKOUT_EXERCISE, EXERCISE_SET, TEMPLATE_EXERCISE, MEAL_ITEM, SCAN_ITEM, HABIT_LOG }
    private record Spec(String table, Scope scope, Set<String> columns, boolean singleton) { Spec { columns = Set.copyOf(columns); } }
    private static final String USER_ID = "user_id";
    private static final String ID = "id";
    private static Set<String> columns(String value) { return Set.of(value.split(",")); }
    private static Spec owned(String table, String cols) { return new Spec(table, Scope.USER, columns(cols), false); }
    private static Spec catalog(String table, String cols) { return new Spec(table, Scope.CATALOG, columns(cols), false); }
    private static Spec child(String table, Scope scope, String cols) { return new Spec(table, scope, columns(cols), false); }
    private static Spec profile() { return new Spec("fitness_profile", Scope.USER, columns("display_name,goal,fitness_level,equipment,limitations,activity_target,weekly_minutes,sleep_target_hours,step_target,calorie_target,protein_target_g,water_target_oz,target_weight_lb"), true); }

    /**
     * Inclusive lower/upper bound for a single numeric column. A null bound means unbounded on that
     * side, and {@code exclusive} turns the minimum into a strict "greater than" check.
     */
    private record Bound(java.math.BigDecimal min, boolean exclusive, java.math.BigDecimal max) {
        static Bound atLeast(long min) { return new Bound(java.math.BigDecimal.valueOf(min), false, null); }
        static Bound between(long min, long max) { return new Bound(java.math.BigDecimal.valueOf(min), false, java.math.BigDecimal.valueOf(max)); }
        static Bound above(long min) { return new Bound(java.math.BigDecimal.valueOf(min), true, null); }
    }

    /**
     * Server-side range checks, keyed by table then column.
     *
     * <p>HTML {@code min}/{@code max} attributes are client hints a direct API caller ignores, so the
     * bounds that actually protect the data live here. Only the fitness profile is constrained today;
     * the other owned resources keep their existing permissive behaviour.
     */
    private static final Map<String, Map<String, Bound>> BOUNDS = Map.of(
            "fitness_profile", Map.of(
                    "activity_target", Bound.between(1, 14),
                    "weekly_minutes", Bound.atLeast(30),
                    "step_target", Bound.above(0),
                    "calorie_target", Bound.atLeast(0),
                    "protein_target_g", Bound.atLeast(0),
                    "water_target_oz", Bound.atLeast(0),
                    "sleep_target_hours", Bound.between(0, 24)));

    /**
     * Columns stored as a comma-separated string but exposed as an array in the JSON contract,
     * keyed by table then column.
     *
     * <p>The exercise catalog is the case that matters: {@code secondary_muscles} is text in
     * PostgreSQL, while the declared type is {@code string[]}. Normalising on read keeps the stored
     * format and the database column unchanged, and makes every read of the resource agree.
     *
     * <p>The splitting itself is shared with {@link AppDataService#splitCsv(Object)}, so the list and
     * detail endpoints cannot drift apart from the app-data read.
     */
    private static final Map<String, List<String>> LIST_COLUMNS = Map.of(
            "exercises", List.of("secondary_muscles"));

    /** Upper bound on any list result, so no endpoint can return an unbounded set. */
    static final int MAX_PAGE = 500;

    private static final Map<String, Spec> SPECS = Map.ofEntries(
        Map.entry("profile", profile()), Map.entry("fitness-profile", profile()),
        Map.entry("daily-metrics", owned("daily_metrics", "metric_date,steps,sleep_hours,calories_burned,water_oz,resting_heart_rate,readiness,hrv,active_minutes,stress_level")),
        Map.entry("body-metrics", owned("body_metrics", "metric_date,weight_lb,body_fat_pct,waist_in,chest_in,arm_in,thigh_in")),
        Map.entry("workouts", owned("workouts", "title,workout_type,duration_minutes,calories_burned,intensity,workout_date,completed,notes,perceived_effort,distance_miles")),
        Map.entry("workout-sessions", owned("workout_sessions", "title,workout_type,started_at,completed_at,duration_minutes,notes,perceived_effort,completed")),
        Map.entry("workout-templates", owned("workout_templates", "name,description,workout_type,estimated_minutes,is_favorite")),
        Map.entry("plan-sessions", owned("plan_sessions", "day_index,title,workout_type,intensity,duration_minutes,completed")),
        Map.entry("personal-records", owned("personal_records", "exercise,record_value,unit,achieved_date,previous_value")),
        Map.entry("goals", owned("goals", "goal_type,title,description,start_value,target_value,current_value,unit,start_date,target_date,status")),
        Map.entry("meals", owned("meals", "meal_date,meal_type,name,source,calories,protein_g,carbs_g,fat_g,fiber_g")),
        Map.entry("food-scans", owned("food_scans", "meal_id,status,model,image_path,error")),
        Map.entry("ai-usage", owned("ai_usage", "feature,model,input_tokens,output_tokens,success,estimated_cost")),
        Map.entry("habits", owned("habits", "name,description,icon,target_per_week,color,active")),
        Map.entry("habit-logs", child("habit_logs", Scope.HABIT_LOG, "habit_id,log_date,completed")),
        // Phase 15: timezone and recurrence are user-settable scheduling inputs. Delivery state
        // (delivery_status, delivery_attempts, last_error, last_delivered_at, next_occurrence_at)
        // is deliberately absent so it stays server-controlled.
        Map.entry("reminders", owned("reminders", "type,title,message,scheduled_time,days_of_week,enabled,quiet_hours_start,quiet_hours_end,timezone,recurrence")),
        Map.entry("health-devices", owned("health_devices", "device_name,device_type,status,last_sync")),
        Map.entry("coach-notifications", owned("coach_notifications", "title,message,kind,is_read")),
        Map.entry("exercises", catalog("exercises", "name,description,muscle_group,secondary_muscles,equipment,difficulty,instructions,is_compound")),
        Map.entry("foods", catalog("foods", "name,category,serving_size,serving_unit,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,source")),
        Map.entry("workout-exercises", child("workout_exercises", Scope.WORKOUT_EXERCISE, "workout_session_id,exercise_id,order_index,notes")),
        Map.entry("exercise-sets", child("exercise_sets", Scope.EXERCISE_SET, "workout_exercise_id,set_number,reps,weight,weight_unit,duration_seconds,distance,distance_unit,rpe,completed")),
        Map.entry("workout-template-exercises", child("workout_template_exercises", Scope.TEMPLATE_EXERCISE, "template_id,exercise_id,order_index,target_sets,target_reps,target_weight")),
        Map.entry("meal-items", child("meal_items", Scope.MEAL_ITEM, "meal_id,food_id,food_name,quantity,grams,calories,protein_g,carbs_g,fat_g,fiber_g,source")),
        Map.entry("food-scan-items", child("food_scan_items", Scope.SCAN_ITEM, "scan_id,food_id,food_name,estimated_grams,confirmed_grams,confidence,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,user_edited"))
    );

    public List<Map<String, Object>> list(String resource, String user) {
        Spec spec = require(resource);
        var params = new MapSqlParameterSource();
        String predicate = "";
        if (spec.scope() == Scope.USER) {
            params.addValue("uid", uuid(user, "user id"));
            predicate = " WHERE t." + USER_ID + "=:uid";
        } else if (spec.scope() != Scope.CATALOG) {
            params.addValue("uid", uuid(user, "user id"));
            predicate = childJoin(spec.scope()) + "t.id=t.id";
        }
        return jdbc.queryForList("SELECT t.* FROM " + spec.table() + " t" + predicate + " ORDER BY t.id DESC LIMIT " + MAX_PAGE, params)
                .stream().map(row -> listColumns(spec, row)).toList();
    }

    public Map<String, Object> get(String resource, String id, String user) { return one(resource, id, user); }

    private Map<String, Object> one(String resource, String id, String user) {
        Spec spec = require(resource);
        UUID resourceId = uuid(id, "resource id");
        var params = new MapSqlParameterSource("id", resourceId);
        String predicate;
        if (spec.scope() == Scope.USER) {
            params.addValue("uid", uuid(user, "user id"));
            predicate = " WHERE t." + USER_ID + "=:uid AND t.id=:id";
        } else if (spec.scope() == Scope.CATALOG) {
            predicate = " WHERE t.id=:id";
        } else {
            params.addValue("uid", uuid(user, "user id"));
            predicate = childJoin(spec.scope()) + "t.id=:id";
        }
        return jdbc.queryForList("SELECT t.* FROM " + spec.table() + " t" + predicate + " LIMIT " + MAX_PAGE, params)
            .stream().findFirst().map(row -> listColumns(spec, row))
            .orElseThrow(() -> new NoSuchElementException("Resource not found"));
    }

    /**
     * Replaces any declared list column with its split form, so every read of a resource returns the
     * same shape regardless of which endpoint served it.
     *
     * <p>The row is copied rather than mutated, so the caller's map and the read-only result stay
     * independent. Resources with no declared list columns are returned untouched, which is why this
     * is safe to apply to every resource.
     */
    private static Map<String, Object> listColumns(Spec spec, Map<String, Object> row) {
        List<String> columns = LIST_COLUMNS.get(spec.table());
        if (columns == null) return row;
        Map<String, Object> copy = new LinkedHashMap<>(row);
        for (String column : columns) {
            if (copy.containsKey(column)) copy.put(column, AppDataService.splitCsv(copy.get(column)));
        }
        return copy;
    }

    /** The caller's existing row for a singleton resource, or null when there is none. */
    private Map<String, Object> existingForUser(Spec spec, String user) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + spec.table() + " WHERE " + USER_ID + "=:uid LIMIT 1",
                new MapSqlParameterSource("uid", uuid(user, "user id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public Map<String, Object> create(String resource, Map<String, Object> body, String user) {
        Spec spec = writable(require(resource));
        Map<String, Object> values = values(spec, body, true);
        if (spec.scope() == Scope.USER) values.put(USER_ID, uuid(user, "user id"));
        else proveParent(spec.scope(), values, user);
        // A singleton resource is one row per user, so creating a second one is a client error rather
        // than a unique-constraint violation. Reporting it as such keeps the 400 contract instead of
        // surfacing a 500 from the database.
        if (spec.singleton() && spec.scope() == Scope.USER && existingForUser(spec, user) != null) {
            throw new IllegalArgumentException(resource + " already exists for this account");
        }
        String columns = String.join(",", values.keySet());
        String parameters = values.keySet().stream().map(name -> ":" + name).collect(Collectors.joining(","));
        return jdbc.queryForMap("INSERT INTO " + spec.table() + " (" + columns + ") VALUES (" + parameters + ") RETURNING *",
            new MapSqlParameterSource(values));
    }

    @Transactional
    public Map<String, Object> update(String resource, String id, Map<String, Object> body, String user) {
        Spec spec = writable(require(resource));
        one(resource, id, user);
        Map<String, Object> values = values(spec, body, false);
        if (values.isEmpty()) return get(resource, id, user);
        if (spec.scope() != Scope.USER) proveParent(spec.scope(), values, user);
        String assignments = values.keySet().stream().map(name -> name + "=:" + name).collect(Collectors.joining(","));
        jdbc.update("UPDATE " + spec.table() + " SET " + assignments + " WHERE id=:id", new MapSqlParameterSource(values).addValue("id", uuid(id, "resource id")));
        return one(resource, id, user);
    }

    @Transactional
    public void delete(String resource, String id, String user) {
        // The same guard as create and update: a read-only resource must not be deletable either.
        // Without it, DELETE removed shared catalog rows such as exercises and foods.
        Spec spec = writable(require(resource));
        one(resource, id, user);
        jdbc.update("DELETE FROM " + spec.table() + " WHERE id=:id",
                new MapSqlParameterSource("id", uuid(id, "resource id")));
    }




    private String childJoin(Scope scope) {
        return switch (scope) {
            case WORKOUT_EXERCISE -> " JOIN workout_sessions p ON p.id=t.workout_session_id WHERE p.user_id=:uid AND ";
            case EXERCISE_SET -> " JOIN workout_exercises c ON c.id=t.workout_exercise_id JOIN workout_sessions p ON p.id=c.workout_session_id WHERE p.user_id=:uid AND ";
            case TEMPLATE_EXERCISE -> " JOIN workout_templates p ON p.id=t.template_id WHERE p.user_id=:uid AND ";
            case MEAL_ITEM -> " JOIN meals p ON p.id=t.meal_id WHERE p.user_id=:uid AND ";
            case SCAN_ITEM -> " JOIN food_scans p ON p.id=t.scan_id WHERE p.user_id=:uid AND ";
            case HABIT_LOG -> " JOIN habits p ON p.id=t.habit_id WHERE p.user_id=:uid AND ";
            default -> throw new IllegalStateException("Not a child scope");
        };
    }

    private void proveParent(Scope scope, Map<String, Object> values, String user) {
        String column = switch (scope) {
            case WORKOUT_EXERCISE -> "workout_session_id";
            case EXERCISE_SET -> "workout_exercise_id";
            case TEMPLATE_EXERCISE -> "template_id";
            case MEAL_ITEM -> "meal_id";
            case SCAN_ITEM -> "scan_id";
            case HABIT_LOG -> "habit_id";
            default -> throw new IllegalArgumentException("Parent is server controlled");
        };
        Object rawId = values.remove(column);
        if (rawId == null) throw new IllegalArgumentException(column + " is required");
        UUID parentId = uuid(rawId.toString(), column);
        String proof = switch (scope) {
            case WORKOUT_EXERCISE -> "SELECT 1 FROM workout_sessions WHERE id=:id AND user_id=:uid";
            case EXERCISE_SET -> "SELECT 1 FROM workout_exercises c JOIN workout_sessions p ON p.id=c.workout_session_id WHERE c.id=:id AND p.user_id=:uid";
            case TEMPLATE_EXERCISE -> "SELECT 1 FROM workout_templates WHERE id=:id AND user_id=:uid";
            case MEAL_ITEM -> "SELECT 1 FROM meals WHERE id=:id AND user_id=:uid";
            case SCAN_ITEM -> "SELECT 1 FROM food_scans WHERE id=:id AND user_id=:uid";
            case HABIT_LOG -> "SELECT 1 FROM habits WHERE id=:id AND user_id=:uid";
            default -> throw new IllegalStateException("Not a child scope");
        };
        Integer found = jdbc.queryForObject(proof,
            new MapSqlParameterSource("id", parentId).addValue("uid", uuid(user, "user id")), Integer.class);
        if (found == null) throw new NoSuchElementException("Resource not found");
    }

    private Map<String, Object> values(Spec spec, Map<String, Object> body, boolean creating) {
        if (body == null) throw new IllegalArgumentException("JSON body required");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String name = entry.getKey();
            if (USER_ID.equals(name)) throw new IllegalArgumentException("user_id is server controlled");
            if ("id".equals(name)) continue;
            if (!spec.columns().contains(name)) throw new IllegalArgumentException("Unsupported field: " + name);
            Object value = entry.getValue();
            if (name.endsWith("_date") || name.equals("metric_date") || name.equals("achieved_date") || name.equals("log_date") || name.equals("start_date") || name.equals("target_date")) {
                if (value instanceof String text) value = java.time.LocalDate.parse(text);
            } else if (name.endsWith("_lb") || name.endsWith("_g") || name.endsWith("_value") || name.endsWith("_miles") || name.endsWith("_minutes") || name.endsWith("_calories") || name.endsWith("_steps") || name.endsWith("_oz") || name.endsWith("_hours") || name.endsWith("_reps") || name.endsWith("_weight")) {
                if (value instanceof String text) value = new java.math.BigDecimal(text);
            } else if (name.endsWith("_completed") || name.endsWith("_active") || name.endsWith("_enabled") || name.endsWith("_read") || name.endsWith("_favorite")) {
                if (value instanceof String text) value = Boolean.valueOf(text);
            } else if (name.endsWith("_time")) {
                // Time columns (for example reminder scheduled_time) reject a varchar argument.
                if (value instanceof String text) value = java.sql.Time.valueOf(text.length() == 5 ? text + ":00" : text);
            }
            // The client sends list-shaped values (for example reminder days_of_week) as JSON
            // arrays, while the matching columns are text. Join them into the stored CSV form.
            if (value instanceof java.util.Collection<?> items) {
                value = items.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","));
            }
            result.put(name, name.endsWith("_id") && value instanceof String text ? uuid(text, name) : value);
        }
        if (creating && result.isEmpty()) throw new IllegalArgumentException("At least one field is required");
        checkBounds(spec, result);
        return result;
    }

    /**
     * Enforces the per-column numeric bounds declared in {@link #BOUNDS}.
     *
     * <p>A null value means "leave unchanged" on a partial update, so it is not checked. A non-numeric
     * value is rejected here rather than being handed to the driver, which would surface as a 500.
     */
    private void checkBounds(Spec spec, Map<String, Object> values) {
        Map<String, Bound> rules = BOUNDS.get(spec.table());
        if (rules == null) return;
        for (Map.Entry<String, Bound> rule : rules.entrySet()) {
            if (!values.containsKey(rule.getKey())) continue;
            Object raw = values.get(rule.getKey());
            if (raw == null) continue;
            java.math.BigDecimal number;
            try {
                number = raw instanceof java.math.BigDecimal decimal
                        ? decimal
                        : new java.math.BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(rule.getKey() + " must be a number");
            }
            Bound bound = rule.getValue();
            if (bound.min() != null) {
                int below = number.compareTo(bound.min());
                if (below < 0 || (below == 0 && bound.exclusive())) {
                    throw new IllegalArgumentException(rule.getKey() + " must be "
                            + (bound.exclusive() ? "greater than " : "at least ") + bound.min().toPlainString());
                }
            }
            if (bound.max() != null && number.compareTo(bound.max()) > 0) {
                throw new IllegalArgumentException(rule.getKey() + " must be at most " + bound.max().toPlainString());
            }
        }
    }

    private Spec writable(Spec spec) {
        if (spec.scope() == Scope.CATALOG || "ai_usage".equals(spec.table())) throw new org.springframework.security.access.AccessDeniedException("Resource is read-only");
        return spec;
    }
    private Spec require(String resource) {
        Spec spec = SPECS.get(resource);
        if (spec == null) throw new NoSuchElementException("Unknown resource");
        return spec;
    }
    private static UUID uuid(String value, String label) {
        try { return UUID.fromString(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid " + label); }
    }
}
