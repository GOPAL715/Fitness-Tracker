package com.fittrack.analytics;

import com.fittrack.api.CalendarController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final JdbcTemplate jdbc;
    public AnalyticsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard(@AuthenticationPrincipal String user) {
        LocalDate today=LocalDate.now();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("date",today);
        result.put("activity",one("SELECT COALESCE(steps,0) steps,COALESCE(active_minutes,0) active_minutes,COALESCE(sleep_hours,0) sleep_hours,COALESCE(calories_burned,0) calories_burned,COALESCE(water_oz,0) water_oz,COALESCE(readiness,0) readiness FROM daily_metrics WHERE user_id=? AND metric_date=?",user,today));
        result.put("nutrition",one("SELECT COALESCE(sum(calories),0) calories,COALESCE(sum(protein_g),0) protein_g,COALESCE(sum(carbs_g),0) carbs_g,COALESCE(sum(fat_g),0) fat_g FROM meals WHERE user_id=? AND meal_date=?",user,today));
        result.put("workout",one("SELECT count(*) sessions,COALESCE(sum(duration_minutes),0) minutes,COALESCE(sum(calories_burned),0) calories FROM workouts WHERE user_id=? AND workout_date=? AND completed=true",user,today));
        result.put("body",one("SELECT weight_lb,body_fat_pct FROM body_metrics WHERE user_id=? AND metric_date=?",user,today));
        result.put("targets",one("SELECT step_target,calorie_target,protein_target_g,water_target_oz,target_weight_lb FROM fitness_profile WHERE user_id=?",user));
        return result;
    }

    private LocalDate[] range(LocalDate from, LocalDate to, int defaultDays) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(defaultDays - 1L) : from;
        if (start.isAfter(end)) throw new IllegalArgumentException("from must not be after to");
        if (ChronoUnit.DAYS.between(start, end) > 365) throw new IllegalArgumentException("range must not exceed 366 days");
        return new LocalDate[]{start, end};
    }
    private void authenticated(String user) { if (user == null || user.isBlank()) throw new IllegalArgumentException("Authenticated user is required"); }

    @GetMapping("/workouts")
    public List<Map<String,Object>> workouts(@AuthenticationPrincipal String user, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        authenticated(user); LocalDate[] r=range(from,to,90);
        return jdbc.queryForList("SELECT workout_date::date date,count(*) FILTER (WHERE completed) sessions,COALESCE(sum(duration_minutes) FILTER (WHERE completed),0) minutes,COALESCE(sum(calories_burned) FILTER (WHERE completed),0) calories FROM workouts WHERE user_id=? AND workout_date BETWEEN ? AND ? GROUP BY workout_date ORDER BY workout_date",user,r[0],r[1]);
    }

    @GetMapping("/nutrition")
    public Map<String,Object> nutrition(@AuthenticationPrincipal String user, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        authenticated(user); LocalDate[] r=range(from,to,90); Map<String,Object> result=new LinkedHashMap<>(); result.put("from",r[0]); result.put("to",r[1]);
        result.put("daily",jdbc.queryForList("SELECT meal_date::date date,COALESCE(sum(calories),0) calories,COALESCE(sum(protein_g),0) protein_g,COALESCE(sum(carbs_g),0) carbs_g,COALESCE(sum(fat_g),0) fat_g FROM meals WHERE user_id=? AND meal_date BETWEEN ? AND ? GROUP BY meal_date ORDER BY meal_date",user,r[0],r[1]));
        result.put("totals",one("SELECT COALESCE(sum(calories),0) calories,COALESCE(sum(protein_g),0) protein_g,COALESCE(sum(carbs_g),0) carbs_g,COALESCE(sum(fat_g),0) fat_g FROM meals WHERE user_id=? AND meal_date BETWEEN ? AND ?",user,r[0],r[1])); return result;
    }

    @GetMapping("/progress")
    public Map<String,Object> progress(@AuthenticationPrincipal String user, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        authenticated(user); LocalDate[] r=range(from,to,90); Map<String,Object> result=new LinkedHashMap<>(); result.put("from",r[0]); result.put("to",r[1]);
        result.put("body",jdbc.queryForList("SELECT metric_date::date date,weight_lb,body_fat_pct,waist_in FROM body_metrics WHERE user_id=? AND metric_date BETWEEN ? AND ? ORDER BY metric_date",user,r[0],r[1]));
        result.put("activity",jdbc.queryForList("SELECT metric_date::date date,steps,active_minutes,sleep_hours FROM daily_metrics WHERE user_id=? AND metric_date BETWEEN ? AND ? ORDER BY metric_date",user,r[0],r[1]));
        result.put("target",one("SELECT target_weight_lb FROM fitness_profile WHERE user_id=?",user)); return result;
    }

    @GetMapping("/weekly")
    public Map<String,Object> weekly(@AuthenticationPrincipal String user, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        authenticated(user); LocalDate[] r=range(from,to,7); Map<String,Object> result=new LinkedHashMap<>(); result.put("from",r[0]); result.put("to",r[1]);
        result.put("activity",jdbc.queryForList("SELECT metric_date::date date,COALESCE(steps,0) steps,COALESCE(active_minutes,0) active_minutes,COALESCE(sleep_hours,0) sleep_hours,COALESCE(calories_burned,0) calories_burned FROM daily_metrics WHERE user_id=? AND metric_date BETWEEN ? AND ? ORDER BY metric_date",user,r[0],r[1]));
        result.put("nutrition",jdbc.queryForList("SELECT meal_date::date date,COALESCE(sum(calories),0) calories,COALESCE(sum(protein_g),0) protein_g FROM meals WHERE user_id=? AND meal_date BETWEEN ? AND ? GROUP BY meal_date ORDER BY meal_date",user,r[0],r[1]));
        result.put("workouts",jdbc.queryForList("SELECT workout_date::date date,count(*) FILTER (WHERE completed) sessions,COALESCE(sum(duration_minutes) FILTER (WHERE completed),0) minutes FROM workouts WHERE user_id=? AND workout_date BETWEEN ? AND ? GROUP BY workout_date ORDER BY workout_date",user,r[0],r[1])); return result;
    }

    @GetMapping("/activity") public List<Map<String,Object>> activity(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){return (List<Map<String,Object>>) progress(u,from,to).get("activity");}
    @GetMapping("/body") public List<Map<String,Object>> body(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){return (List<Map<String,Object>>) progress(u,from,to).get("body");}
    @GetMapping("/habits") public List<Map<String,Object>> habits(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,7);return jdbc.queryForList("SELECT h.name,count(l.id) FILTER(WHERE l.completed) completed FROM habits h LEFT JOIN habit_logs l ON l.habit_id=h.id AND l.log_date BETWEEN ? AND ? WHERE h.user_id=? GROUP BY h.id,h.name ORDER BY h.name",r[0],r[1],u);}
    @GetMapping("/calendar") public Object calendar(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,31);return new CalendarController(jdbc).range(r[0],r[1],u);}
    private Map<String,Object> one(String sql,Object... args){return jdbc.queryForMap(sql,args);}
}