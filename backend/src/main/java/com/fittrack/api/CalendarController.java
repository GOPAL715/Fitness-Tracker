package com.fittrack.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {
    private final JdbcTemplate jdbc;
    public CalendarController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping({"/{date}", "/day/{date}"})
    public Map<String,Object> day(@PathVariable LocalDate date, @AuthenticationPrincipal String userId) {
        return Map.of("date", date, "summary", summary(date, userId));
    }

    @GetMapping({"/{from}/{to}", "/range/{from}/{to}"})
    public List<Map<String,Object>> range(@PathVariable LocalDate from, @PathVariable LocalDate to,
                                           @AuthenticationPrincipal String userId) {
        if (from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
        if (from.plusDays(366).isBefore(to)) throw new IllegalArgumentException("range must not exceed 366 days");
        List<Map<String,Object>> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1))
            result.add(Map.of("date", date, "summary", summary(date, userId)));
        return result;
    }

    private Map<String,Object> summary(LocalDate date, String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Authenticated user is required");
        List<Map<String,Object>> metricRows = jdbc.queryForList(
            "SELECT COALESCE(steps,0) AS steps, COALESCE(water_oz,0) AS water_oz, " +
            "COALESCE(calories_burned,0) AS calories_burned, COALESCE(active_minutes,0) AS active_minutes, " +
            "COALESCE(sleep_hours,0) AS sleep_hours FROM daily_metrics WHERE user_id=CAST(? AS uuid) AND metric_date=?",
            userId, date);
        Map<String,Object> metrics = metricRows.isEmpty()
            ? Map.of("steps", 0, "water_oz", 0, "calories_burned", 0, "active_minutes", 0, "sleep_hours", 0)
            : metricRows.get(0);
        Integer workouts = jdbc.queryForObject("SELECT COUNT(*) FROM workouts WHERE user_id=CAST(? AS uuid) AND workout_date=?", Integer.class, userId, date);
        Integer sessions = jdbc.queryForObject("SELECT COUNT(*) FROM workout_sessions WHERE user_id=CAST(? AS uuid) AND started_at::date=?", Integer.class, userId, date);
        Integer meals = jdbc.queryForObject("SELECT COUNT(*) FROM meals WHERE user_id=CAST(? AS uuid) AND meal_date=?", Integer.class, userId, date);
        Integer habits = jdbc.queryForObject("SELECT COUNT(*) FROM habit_logs WHERE user_id=CAST(? AS uuid) AND log_date=? AND completed=true", Integer.class, userId, date);
        Map<String,Object> summary = new LinkedHashMap<>(metrics);
        summary.put("workouts", workouts + sessions);
        summary.put("meals", meals);
        summary.put("habits_completed", habits);
        return summary;
    }
}

